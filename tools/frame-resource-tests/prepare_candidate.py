#!/usr/bin/env python3
"""Prepare and test a review candidate ONLY in the audit scratch tree.
This does not modify app/src or build an APK. Primary checkout application requires
separate approval review after candidate evidence is available.
"""
from pathlib import Path
import shutil,difflib
root=Path(__file__).resolve().parents[2]
out=Path((root/'docs/performance-audits/current-frame-resource-audit-path.txt').read_text().strip())
candidate=out/'candidate-v2'
assert candidate!=root and candidate.parent==out
candidate.mkdir(exist_ok=False)
for p in (out/'originals/app').rglob('*.java'):
 d=candidate/p.relative_to(out/'originals');d.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,d)
def edit(rel,fn):
 p=candidate/'app/src/main/java/com/winlator'/rel;p.write_text(fn(p.read_text()))
def drawable(s):
 s=s.replace('''        super(id);
        this.texture = new Texture();''','''        this(id, width, height, visual, true);
    }

    /** Imported pixmaps already supply storage: no discarded CPU or AHB allocation. */
    public Drawable(int id, int width, int height, Visual visual, boolean allocateStorage) {
        super(id);
        this.texture = allocateStorage ? new Texture() : null;''')
 s=s.replace('''        if (Drawable.DRAWABLE_FOR_ASR) {''','''        if (!allocateStorage) return;
        if (Drawable.DRAWABLE_FOR_ASR) {''',1)
 return s
edit('xserver/Drawable.java',drawable)
edit('xserver/DrawableManager.java',lambda s:s.replace('''    public void removeDrawable(int id) {''','''    public Drawable createImportedDrawable(int id, short width, short height, byte depth) {
        if (id == 0 || drawables.indexOfKey(id) >= 0) return null;
        Visual visual = xServer.pixmapManager.getVisualForDepth(depth);
        if (visual == null) return null;
        Drawable drawable = new Drawable(id, width, height, visual, false);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {'''))
edit('renderer/Texture.java',lambda s:s.replace('''            allocateTexture(drawable.width, drawable.height, data);
        }''','''            allocateTexture(drawable.width, drawable.height, data);
            needsUpdate = false;
        }'''))
edit('renderer/GPUImage.java',lambda s:s.replace('''        if (isAllocated()) return;
        super.allocateTexture''','''        if (isAllocated() || hardwareBufferPtr == 0) return;
        super.allocateTexture''').replace('''                hardwareBufferPtr = 0;
            }
        }
    }

    @Override
    public void updateFromDrawable''','''                hardwareBufferPtr = 0;
                virtualData = null;
                super.destroy();
            }
        }
    }

    @Override
    public void updateFromDrawable'''))
def ahb(s):
 s=s.replace('''    private final Object fenceLock = new Object();''','''    private final Object fenceLock = new Object();
    private boolean destroyed;''')
 s=s.replace('''        synchronized (fenceLock) {
            if (swapchainFences[slot] >= 0) {''','''        synchronized (fenceLock) {
            // SurfaceControl can report release after the pixmap was destroyed.
            if (destroyed) {
                if (fence >= 0) nativeCloseFd(fence);
                return;
            }
            if (swapchainFences[slot] >= 0) {''')
 s=s.replace('''    public void destroy() {
        releaseScanoutSources();''','''    public void destroy() {
        synchronized (fenceLock) {
            if (destroyed) return;
            destroyed = true;
        }
        releaseScanoutSources();''')
 s=s.replace('''        super.destroy();
    }''','''        virtualData = null;
        super.destroy();
    }''')
 return s
edit('renderer/AHBImage.java',ahb)
def shm(s):
 s=s.replace('import android.os.SharedMemory;','import android.os.SharedMemory;\nimport android.os.ParcelFileDescriptor;\nimport java.io.IOException;')
 s=s.replace('''        private ByteBuffer data;''','''        private ByteBuffer data;
        private int attachments;
        private boolean deleted;''')
 a=s.index('    public void delete(int shmid)');b=s.index('    private static int createSharedMemory',a)
 s=s[:a]+'''    public void delete(int shmid) {
        synchronized (shmemories) {
            SHMemory memory = shmemories.get(shmid);
            if (memory == null) return;
            memory.deleted = true;
            if (memory.fd != -1) {
                XConnectorEpoll.closeFd(memory.fd);
                memory.fd = -1;
            }
            // Closing the server FD must not invalidate another client's attachment.
            if (memory.attachments == 0) shmemories.remove(shmid);
        }
    }

    public void deleteAll() {
        synchronized (shmemories) {
            for (int i = shmemories.size() - 1; i >= 0; i--) delete(shmemories.keyAt(i));
        }
    }

    public ByteBuffer attach(int shmid) {
        synchronized (shmemories) {
            SHMemory memory = shmemories.get(shmid);
            if (memory == null || memory.deleted) return null;
            if (memory.data == null) memory.data = mapSHMSegment(memory.fd, memory.size, 0, true);
            if (memory.data != null) memory.attachments++;
            return memory.data;
        }
    }

    public void detach(ByteBuffer data) {
        if (data == null) return;
        synchronized (shmemories) {
            for (int i = 0; i < shmemories.size(); i++) {
                SHMemory memory = shmemories.valueAt(i);
                if (memory.data != data) continue;
                if (--memory.attachments == 0) {
                    unmapSHMSegment(memory.data, memory.size);
                    memory.data = null;
                    if (memory.deleted) shmemories.remove(shmemories.keyAt(i));
                }
                break;
            }
        }
    }

'''+s[b:]
 s=s.replace('''                SharedMemory sharedMemory = SharedMemory.create(name, size);
                Method getFdMethod = sharedMemory.getClass().getMethod("getFd");
                Integer fd = (Integer) getFdMethod.invoke(sharedMemory);
                return fd != null ? fd : -1;
                }''','''                try (SharedMemory sharedMemory = SharedMemory.create(name, size)) {
                    Method getFdMethod = sharedMemory.getClass().getMethod("getFd");
                    Integer fd = (Integer) getFdMethod.invoke(sharedMemory);
                    // getFd is borrowed; return an independent descriptor to the caller.
                    return fd != null ? ParcelFileDescriptor.fromFd(fd).detachFd() : -1;
                }
            }''').replace('''InvocationTargetException e)''','''InvocationTargetException | IOException e)''')
 return s
edit('sysvshm/SysVSharedMemory.java',shm)
def dri3(s):
 s=s.replace('''import com.winlator.xserver.errors.BadAlloc;''','''import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;''')
 a=s.index('        Window window =',s.index('    private void pixmapFromBuffer('));b=s.index('\n    private void pixmapFromBuffers(',a)
 s=s[:a]+'''        int fd = inputStream.getAncillaryFd();
        try {
            validateTarget(client, pixmapId, windowId);
            int transferred = fd;
            fd = -1; // the import helper now owns the descriptor, including failure
            pixmapFromFd(client, pixmapId, width, height, Short.toUnsignedInt(stride), 0, depth, transferred, size);
        } finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
'''+s[b:]
 a=s.index('        Window window =',s.index('    private void pixmapFromBuffers('));b=s.index('\n    @Override\n    public void handleRequest',a)
 s=s[:a]+'''        int fd = inputStream.getAncillaryFd();
        try {
            validateTarget(client, pixmapId, windowId);
            if (modifiers != 1255 && modifiers != 1274) throw new BadImplementation();
            int transferred = fd;
            fd = -1;
            if (modifiers == 1255) pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, transferred);
            else pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, transferred, (long)stride * height);
        } finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    private void validateTarget(XClient client, int pixmapId, int windowId) throws XRequestError {
        if (client.xServer.windowManager.getWindow(windowId) == null) throw new BadWindow(windowId);
        if (client.xServer.drawableManager.getDrawable(pixmapId) != null ||
                client.xServer.pixmapManager.getPixmap(pixmapId) != null) throw new BadIdChoice(pixmapId);
    }

    private void validateStorage(XClient client, int pixmapId, short width, short height, byte depth, int fd)
            throws XRequestError {
        if (fd < 0) throw new BadAlloc();
        if (width <= 0 || height <= 0) throw new BadValue(width <= 0 ? width : height);
        if (client.xServer.pixmapManager.getVisualForDepth(depth) == null) throw new BadMatch();
        if (client.xServer.drawableManager.getDrawable(pixmapId) != null ||
                client.xServer.pixmapManager.getPixmap(pixmapId) != null) throw new BadIdChoice(pixmapId);
    }

    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd)
            throws IOException, XRequestError {
        NativeTexture image = null;
        Drawable drawable = null;
        boolean installed = false;
        try {
            validateStorage(client, pixmapId, width, height, depth, fd);
            image = Drawable.IS_ASR() ? new AHBImage(fd) : new GPUImage(fd);
            long ptr = image instanceof AHBImage ? ((AHBImage)image).getHardwareBufferPtr()
                    : ((GPUImage)image).getHardwareBufferPtr();
            if (ptr == 0) throw new BadAlloc();
            // DRI3 dimensions are logical pixels. Imported AHB CPU stride may be unset.
            drawable = client.xServer.drawableManager.createImportedDrawable(pixmapId, width, height, depth);
            if (drawable == null) throw new BadIdChoice(pixmapId);
            drawable.setTexture(image);
            if (client.xServer.pixmapManager.createPixmap(drawable) == null) throw new BadIdChoice(pixmapId);
            installed = true;
        } finally {
            if (!installed) {
                if (drawable != null) client.xServer.drawableManager.removeDrawable(pixmapId);
                else if (image != null) image.destroy();
            }
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset,
            byte depth, int fd, long size) throws IOException, XRequestError {
        ByteBuffer buffer = null;
        Drawable drawable = null;
        boolean installed = false;
        try {
            validateStorage(client, pixmapId, width, height, depth, fd);
            if (stride < (long)width * 4 || stride % 4 != 0 || stride / 4 > Short.MAX_VALUE ||
                    offset < 0 || size < (long)stride * height || size > Integer.MAX_VALUE) throw new BadValue(stride);
            buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
            if (buffer == null) throw new BadAlloc();
            // Preserve existing storage stride semantics on the CPU-copy fallback.
            short totalWidth = (short)(stride / 4);
            drawable = client.xServer.drawableManager.createImportedDrawable(pixmapId, totalWidth, height, depth);
            if (drawable == null) throw new BadIdChoice(pixmapId);
            drawable.setData(buffer);
            drawable.setOnDestroyListener(onDestroyDrawableListener);
            if (client.xServer.pixmapManager.createPixmap(drawable) == null) throw new BadIdChoice(pixmapId);
            installed = true;
        } finally {
            if (!installed) {
                if (drawable != null) client.xServer.drawableManager.removeDrawable(pixmapId);
                else if (buffer != null) SysVSharedMemory.unmapSHMSegment(buffer, buffer.capacity());
            }
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
'''+s[b:]
 return s
edit('xserver/extensions/DRI3Extension.java',dri3)
edit('xserver/SHMSegmentManager.java',lambda s:s.replace('    public ByteBuffer getData(int xid) {', '''    /** Called only after the X socket dispatcher has stopped using these buffers. */
    public void detachAll() {
        for (int i = shmSegments.size() - 1; i >= 0; i--) detach(shmSegments.keyAt(i));
    }

    public ByteBuffer getData(int xid) {'''))
edit('xenvironment/components/XServerComponent.java',lambda s:s.replace('''            connector = null;
            PresentExtension present''','''            connector = null;
            // connector.stop has joined all socket readers before mappings are released.
            var segments = xServer.getSHMSegmentManager();
            if (segments != null) segments.detachAll();
            PresentExtension present'''))
patch=''
for p in candidate.rglob('*.java'):
 rel=p.relative_to(candidate);before=(out/'originals'/rel).read_text().splitlines(True)
 patch+=''.join(difflib.unified_diff(before,p.read_text().splitlines(True),fromfile='a/'+str(rel),tofile='b/'+str(rel)))
(out/'candidate.patch').write_text(patch)
print('Review candidate only:',candidate)
