#!/usr/bin/env python3
"""Full-colour X cursors through the real libX11 + libXrender + libXcursor of the host.

A small X server on TCP answers the connection and the core requests libXcursor sends, and answers RENDER with the bytes in
app/src/test/resources/render/*.bin (RenderExtensionTest checks that RenderExtension.java writes exactly these bytes).
  production: RENDER 0.7 is offered -> libXcursor must report ARGB support and build the cursor with CreatePixmap(depth 32),
              PutImage(depth 32, the image's own pixels), RENDER CreatePicture(ARGB32), RENDER CreateCursor(hotspot) and no
              core CreateCursor (the two-colour, 1-bit-mask cursor).
  mutation:   no RENDER (the server before 1.3.54) -> libXcursor must fall back to the core two-colour cursor.
Exit 0 only when production passes and the mutation is detected. Usage: run_render_cursor_regressions.py [--write-golden]
"""
import ctypes, socket, struct, sys, threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / 'app/src/test/resources/render'
ROOT_VISUAL, ROOT_WINDOW = 0x21, 0x100
RENDER_MAJOR, RENDER_FIRST_ERROR = 149, 128
FORMAT_ARGB32, FORMAT_RGB32 = 0x50001, 0x50002

def golden_replies():
    version = struct.pack('<BBHIII16x', 1, 0, 0, 0, 0, 7)
    fmt = lambda i, a, m: struct.pack('<IBB2x8HI', i, 1, 32, 16, 0xff, 8, 0xff, 0, 0xff, a, m, 0)
    body = fmt(FORMAT_ARGB32, 24, 0xff) + fmt(FORMAT_RGB32, 0, 0)
    body += struct.pack('<II', 1, FORMAT_RGB32) + struct.pack('<BxH4x', 32, 1) + struct.pack('<II', ROOT_VISUAL, FORMAT_RGB32)
    body += struct.pack('<I', 0)
    formats = struct.pack('<BBHIIIIII4x', 1, 0, 0, len(body) // 4, 2, 1, 1, 1, 1) + body
    filters = struct.pack('<BBHIII16x', 1, 0, 0, 0, 0, 0)
    return {'version': version, 'pictformats': formats, 'filters': filters}

def setup_reply():
    vendor = b'Elbrus Technologies, LLC'
    vpad = vendor + b'\0' * (-len(vendor) % 4)
    formats = b''.join(struct.pack('<BBB5x', d, b, 32) for d, b in ((1, 1), (24, 32), (32, 32)))
    visual = struct.pack('<IBBHIII4x', ROOT_VISUAL, 4, 8, 256, 0xff0000, 0xff00, 0xff)
    depth = struct.pack('<BxH4x', 32, 1) + visual
    screen = struct.pack('<IIIIIHHHHHHIBBBB', ROOT_WINDOW, 0x20, 0xffffff, 0, 0, 1344, 608, 300, 140, 1, 1, ROOT_VISUAL, 0, 0, 32, 1) + depth
    body = struct.pack('<IIIIHHBBBBBBBB4x', 0, 0x00200000, 0x001fffff, 256, len(vendor), 65535, 1, 3, 0, 0, 32, 32, 8, 255)
    body += vpad + formats + screen
    return struct.pack('<BxHHH', 1, 11, 0, len(body) // 4) + body

class FakeServer(threading.Thread):
    def __init__(self, with_render, replies):
        super().__init__(daemon=True)
        self.with_render, self.replies, self.log = with_render, replies, []
        self.sock = socket.socket(); self.sock.bind(('127.0.0.1', 0)); self.sock.listen(1)
        self.port = self.sock.getsockname()[1]

    def recv(self, conn, n):
        data = b''
        while len(data) < n:
            chunk = conn.recv(n - len(data))
            if not chunk: raise EOFError
            data += chunk
        return data

    def run(self):
        conn, _ = self.sock.accept()
        try:
            head = self.recv(conn, 12)
            n, d = struct.unpack_from('<HH', head, 6)
            self.recv(conn, (n + 3) // 4 * 4 + (d + 3) // 4 * 4)
            conn.sendall(setup_reply())
            seq = 0
            while True:
                op, data, length = struct.unpack('<BBH', self.recv(conn, 4))
                body = self.recv(conn, length * 4 - 4) if length else b''
                seq = (seq + 1) & 0xffff
                self.log.append((op, data, body))
                reply = self.answer(op, data, body)
                if reply is not None: conn.sendall(reply[:2] + struct.pack('<H', seq) + reply[4:])
        except (EOFError, ConnectionError):
            pass
        finally:
            conn.close()

    def answer(self, op, data, body):
        if op == 98:  # QueryExtension
            n, = struct.unpack_from('<H', body, 0); name = body[4:4 + n]
            if name == b'RENDER' and self.with_render:
                return struct.pack('<BxHIBBBB20x', 1, 0, 0, 1, RENDER_MAJOR, 0, RENDER_FIRST_ERROR)
            return struct.pack('<BxHIB23x', 1, 0, 0, 0)
        if op == 20: return struct.pack('<BBHIIII12x', 1, 0, 0, 0, 0, 0, 0)  # GetProperty: none
        if op == 43: return struct.pack('<BBHII20x', 1, 0, 0, 0, ROOT_WINDOW)  # GetInputFocus (XSync)
        if op == RENDER_MAJOR:
            return {0: self.replies['version'], 1: self.replies['pictformats'], 29: self.replies['filters']}.get(data)
        return None

class XcursorImage(ctypes.Structure):
    _fields_ = [('version', ctypes.c_uint32), ('size', ctypes.c_uint32), ('width', ctypes.c_uint32), ('height', ctypes.c_uint32),
                ('xhot', ctypes.c_uint32), ('yhot', ctypes.c_uint32), ('delay', ctypes.c_uint32), ('pixels', ctypes.POINTER(ctypes.c_uint32))]

def run_client(port, pixels, w, h, hot):
    x11 = ctypes.CDLL('libX11.so.6'); xc = ctypes.CDLL('libXcursor.so.1')
    x11.XOpenDisplay.restype = ctypes.c_void_p; x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]; x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    xc.XcursorSupportsARGB.argtypes = [ctypes.c_void_p]
    xc.XcursorImageCreate.restype = ctypes.POINTER(XcursorImage); xc.XcursorImageCreate.argtypes = [ctypes.c_int, ctypes.c_int]
    xc.XcursorImageLoadCursor.restype = ctypes.c_ulong; xc.XcursorImageLoadCursor.argtypes = [ctypes.c_void_p, ctypes.POINTER(XcursorImage)]
    dpy = x11.XOpenDisplay(f'127.0.0.1:{port - 6000}'.encode())
    if not dpy: raise SystemExit('XOpenDisplay failed')
    argb = xc.XcursorSupportsARGB(dpy)
    img = xc.XcursorImageCreate(w, h); img.contents.xhot, img.contents.yhot = hot
    for i, p in enumerate(pixels): img.contents.pixels[i] = p
    cursor = xc.XcursorImageLoadCursor(dpy, img)
    x11.XSync(dpy, 0); x11.XCloseDisplay(dpy)
    return argb, cursor

def check(with_render, replies):
    w, h, hot = 4, 2, (1, 1)
    # Premultiplied ARGB: opaque gold, half-transparent shadow, transparent.
    pixels = [0xffd4a017, 0x80000000, 0x00000000, 0xff8b4513, 0x40202020, 0xffffffff, 0x00000000, 0xc0806040]
    port = None
    for p in range(6090, 6190):  # Xlib's TCP displays are 6000 + n
        try:
            server = FakeServer(with_render, replies)
            server.sock.close(); server.sock = socket.socket(); server.sock.bind(('127.0.0.1', p)); server.sock.listen(1)
            server.port = port = p; break
        except OSError:
            continue
    server.start()
    argb, cursor = run_client(port, pixels, w, h, hot)
    server.join(5)
    ops = [(op, data) for op, data, _ in server.log]
    problems = []
    if with_render:
        if argb != 1: problems.append('libXcursor does not see ARGB cursor support')
        put = [b for op, d, b in server.log if op == 72]
        if not any(struct.unpack_from('<B', b, 17)[0] == 32 and b[20:20 + w * h * 4] == struct.pack(f'<{w*h}I', *pixels) for b in put):
            problems.append('no depth-32 PutImage carrying the cursor pixels')
        if not any(op == 53 and d == 32 for op, d in ops): problems.append('no depth-32 CreatePixmap')
        pict = [b for op, d, b in server.log if op == RENDER_MAJOR and d == 4]
        if not pict or struct.unpack_from('<I', pict[0], 8)[0] != FORMAT_ARGB32: problems.append('no RENDER CreatePicture with ARGB32')
        cc = [b for op, d, b in server.log if op == RENDER_MAJOR and d == 27]
        if not cc or struct.unpack_from('<HH', cc[0], 8) != hot: problems.append('no RENDER CreateCursor at the hotspot')
        if any(op == 93 for op, _ in ops): problems.append('core two-colour CreateCursor was used')
    else:
        if argb: problems.append('ARGB reported without RENDER')
        if not any(op == 93 for op, _ in ops): problems.append('no core CreateCursor fallback')
    print(('RENDER 0.7' if with_render else 'no RENDER'), 'ARGB', argb, 'cursor', hex(cursor), 'requests', ops)
    return problems

def main():
    replies = golden_replies()
    if '--write-golden' in sys.argv:
        GOLDEN.mkdir(parents=True, exist_ok=True)
        for name, data in replies.items(): (GOLDEN / f'{name}.bin').write_bytes(data)
    for name, data in replies.items():
        assert (GOLDEN / f'{name}.bin').read_bytes() == data, f'{name}.bin differs from the spec here'
    prod = check(True, replies)
    mut = check(False, replies)
    print('production:', 'PASS' if not prod else 'FAIL ' + '; '.join(prod))
    print('no-RENDER mutation:', 'detected (two-colour fallback)' if not mut else 'NOT as expected ' + '; '.join(mut))
    sys.exit(0 if not prod and not mut else 1)

if __name__ == '__main__':
    main()
