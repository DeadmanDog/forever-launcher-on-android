"""Create a scratch-only candidate; never writes app/src in the checkout."""
from pathlib import Path
import shutil,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]).resolve();candidate=out/'candidate';assert not candidate.exists();assert root/'app/src' not in candidate.parents
names=['renderer/Texture.java','renderer/VertexAttribute.java','renderer/material/ShaderMaterial.java','renderer/EffectComposer.java','renderer/ASurfaceRenderer.java','xserver/XClient.java','xserver/WindowManager.java','xserver/extensions/MITSHMExtension.java']
for name in names:
 src=root/'app/src/main/java/com/winlator'/name
 for folder in [out/'originals',candidate]:
  dest=folder/'app/src/main/java/com/winlator'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dest)
# Copy all untouched sources exercised by the host harness.
for name in ['renderer/RenderTarget.java','renderer/effects/Effect.java','renderer/effects/RenderScaleEffect.java','renderer/effects/SourceTextureFilterEffect.java','renderer/ViewTransformation.java','renderer/XServerRenderer.java','xserver/ListenerSnapshot.java','xserver/Bitmask.java','xserver/EventListener.java','xserver/events/Event.java']:
 dest=candidate/'app/src/main/java/com/winlator'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/'app/src/main/java/com/winlator'/name,dest)
def edit(name,old,new):
 p=candidate/'app/src/main/java/com/winlator'/name;s=p.read_text();assert old in s,name;s=s.replace(old,new,1);p.write_text(s)
edit('renderer/Texture.java','''        this.minFilter = minFilter;\n        this.magFilter = magFilter;''','''        if (this.minFilter == minFilter && this.magFilter == magFilter) return;\n        this.minFilter = minFilter;\n        this.magFilter = magFilter;''')
edit('renderer/VertexAttribute.java','    private int location = -1;','    private int location = -1;\n    private int locationProgramId = -1;')
edit('renderer/VertexAttribute.java','''        if (location == -1) location = GLES20.glGetAttribLocation(programId, name);\n        GLES20.glBindBuffer''','''        if (locationProgramId != programId) {\n            location = GLES20.glGetAttribLocation(programId, name);\n            locationProgramId = programId;\n        }\n        if (location < 0) return;\n        GLES20.glBindBuffer''')
edit('renderer/VertexAttribute.java','''    public void destroy() {\n        clear();''','''    public void destroy() {\n        clear();\n        location = -1;\n        locationProgramId = -1;''')
p=candidate/'app/src/main/java/com/winlator/renderer/material/ShaderMaterial.java';s=p.read_text();a=s.index('        int programId = GLES20.glCreateProgram();');b=s.index('\n    protected String getVertexShader()',a)
s=s[:a]+'''        int programId = GLES20.glCreateProgram();
        int vertexShaderId = 0;
        int fragmentShaderId = 0;
        boolean linked = false;
        try {
            int[] compiled = new int[1];
            vertexShaderId = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
            GLES20.glShaderSource(vertexShaderId, vertexShader);
            GLES20.glCompileShader(vertexShaderId);
            GLES20.glGetShaderiv(vertexShaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                throw new IllegalStateException("Could not compile vertex shader: " + GLES20.glGetShaderInfoLog(vertexShaderId));
            }
            GLES20.glAttachShader(programId, vertexShaderId);
            fragmentShaderId = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
            GLES20.glShaderSource(fragmentShaderId, fragmentShader);
            GLES20.glCompileShader(fragmentShaderId);
            GLES20.glGetShaderiv(fragmentShaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                throw new IllegalStateException("Could not compile fragment shader: " + GLES20.glGetShaderInfoLog(fragmentShaderId));
            }
            GLES20.glAttachShader(programId, fragmentShaderId);
            GLES20.glLinkProgram(programId);
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                throw new IllegalStateException("Could not link shader program: " + GLES20.glGetProgramInfoLog(programId));
            }
            linked = true;
            return programId;
        } finally {
            if (vertexShaderId != 0) GLES20.glDeleteShader(vertexShaderId);
            if (fragmentShaderId != 0) GLES20.glDeleteShader(fragmentShaderId);
            if (!linked) GLES20.glDeleteProgram(programId);
        }
    }
''' + s[b:]
s=s.replace('private final ArrayMap<String, Integer> uniforms', 'private static final int UNRESOLVED = Integer.MIN_VALUE;\n    private final ArrayMap<String, Integer> uniforms').replace('uniforms.put(name, -1)','uniforms.put(name, UNRESOLVED)').replace('if (location == -1) {','if (location == UNRESOLVED) {').replace('return location != null ? location : -1;','return location != null && location != UNRESOLVED ? location : -1;').replace('''        programId = 0;
    }''','''        programId = 0;
        for (int i = 0; i < uniforms.size(); i++) uniforms.setValueAt(i, UNRESOLVED);
    }''');p.write_text(s)
p=candidate/'app/src/main/java/com/winlator/renderer/EffectComposer.java';s=p.read_text().replace('''        readBuffer.allocateFramebuffer(outputWidth, outputHeight);
        writeBuffer.allocateFramebuffer(outputWidth, outputHeight);''','''        if (snapshot.size() > 1) readBuffer.allocateFramebuffer(outputWidth, outputHeight);
        else readBuffer.destroy();
        if (snapshot.size() > 2) writeBuffer.allocateFramebuffer(outputWidth, outputHeight);
        else writeBuffer.destroy();''')
a=s.index('        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());');b=s.index('\n    public synchronized void invalidateGLResources()',a)
body=s[a:b];last=body.index('        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);',body.index('        for (int i'))
main=body[:last];main=main.replace('''        renderer.drawScene();
        if (scaledScene) {
            renderer.clearRenderTargetSizeOverride();
        }''','''        try {
            renderer.drawScene();
        } finally {
            if (scaledScene) renderer.clearRenderTargetSizeOverride();
        }''')
s=s[:a]+'''        try {
'''+''.join(('    '+line if line else '')+'\n' for line in main.splitlines())+'''        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (scaledScene) GLES20.glViewport(0, 0, outputWidth, outputHeight);
            renderer.setViewportNeedsUpdate(true);
        }
    }
''' +s[b:];p.write_text(s)
edit('xserver/XClient.java','''        if (eventMask.isEmpty()) return;''','''        if (eventMask.isEmpty()) {
            eventListeners.remove(window);
            return;
        }''')
p=candidate/'app/src/main/java/com/winlator/xserver/extensions/MITSHMExtension.java';s=p.read_text().replace('import com.winlator.xserver.errors.BadImplementation;','import com.winlator.xserver.errors.BadImplementation;\nimport com.winlator.xserver.errors.BadValue;\nimport com.winlator.xserver.errors.BadMatch;\nimport com.winlator.xserver.errors.BadAccess;\nimport com.winlator.xserver.events.ShmCompletionNotify;').replace('''            outputStream.writeByte((byte)0);
        }
    }

    private static void attach''','''            outputStream.writeByte((byte)0);
            outputStream.writePad(15);
        }
    }

    private static void attach''')
s=s.replace('private static void putImage(', 'private void putImage(')
s=s.replace('''        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        inputStream.skip(4);''','''        int format = inputStream.readUnsignedByte();
        boolean sendEvent = inputStream.readUnsignedByte() != 0;
        inputStream.skip(1);
        int shmseg = inputStream.readInt();
        long offset = inputStream.readUnsignedInt();''')
s=s.replace('''        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, data, totalWidth, totalHeight);''','''        // The renderer accepts packed ZPixmap and one-bit bitmap input only.
        if (format != 0 && format != 2) throw new BadValue(format);
        if ((format == 0 && depth != 1) || (format == 2 && depth != 24 && depth != 32)) throw new BadMatch();
        int imageW = Short.toUnsignedInt(totalWidth);
        int imageH = Short.toUnsignedInt(totalHeight);
        int x = Short.toUnsignedInt(srcX), y = Short.toUnsignedInt(srcY);
        int w = Short.toUnsignedInt(srcWidth), h = Short.toUnsignedInt(srcHeight);
        if (imageW > Short.MAX_VALUE || imageH > Short.MAX_VALUE || x + w > imageW || y + h > imageH) throw new BadValue(imageW);
        long rowBytes = depth == 1 ? ((imageW + 31L) / 32L) * 4L : imageW * 4L;
        long imageBytes = rowBytes * imageH;
        if ((offset & 3L) != 0 || offset > data.capacity()) throw new BadValue((int)offset);
        if (imageBytes > data.capacity() - offset) throw new BadAccess();
        // Slice without mutating the manager's shared ByteBuffer position/limit.
        ByteBuffer image = data.duplicate().order(data.order());
        image.clear().position((int)offset).limit((int)(offset + imageBytes));
        image = image.slice().order(data.order());
        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, image, totalWidth, totalHeight);
        if (sendEvent) client.sendEvent(new ShmCompletionNotify(firstEventId, drawableId, shmseg, (int)offset));''');p.write_text(s)
p=candidate/'app/src/main/java/com/winlator/xserver/events/ShmCompletionNotify.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('''package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.extensions.MITSHMExtension;
import java.io.IOException;

/** MIT-SHM completion: the synchronous CPU blit no longer reads this segment. */
public final class ShmCompletionNotify extends Event {
    private final int drawableId, shmseg, offset;
    public ShmCompletionNotify(byte eventCode, int drawableId, int shmseg, int offset) {
        super(Byte.toUnsignedInt(eventCode));
        this.drawableId = drawableId;
        this.shmseg = shmseg;
        this.offset = offset;
    }
    @Override public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        outputStream.writeByte(code);
        outputStream.writeByte((byte)0);
        outputStream.writeShort(sequenceNumber);
        outputStream.writeInt(drawableId);
        outputStream.writeShort((short)3);
        outputStream.writeByte(MITSHMExtension.MAJOR_OPCODE);
        outputStream.writeByte((byte)0);
        outputStream.writeInt(shmseg);
        outputStream.writeInt(offset);
        outputStream.writePad(12);
    }
}
''')
p=candidate/'app/src/main/java/com/winlator/renderer/ASurfaceRenderer.java';s=p.read_text();s=s.replace('''            visibleIds.add(contentId);
''','');s=s.replace('''            WindowSurface ws = getOrCreateWindowSurface(contentId, rw.content.width, rw.content.height, debugName);''','''            if (!geometryOk) continue;
            WindowSurface ws = getOrCreateWindowSurface(contentId, rw.content.width, rw.content.height, debugName);
            if (ws == null) continue;
            visibleIds.add(contentId);''');s=s.replace('''            out.dst.set(dstL, dstT, dstR, dstB);
        }

        return adjustRectLT''','''            out.dst.set(dstL, dstT, dstR, dstB);
        } else {
            float scale = viewTransformation.aspect;
            int dstL = viewTransformation.viewOffsetX + Math.round(rootX * scale);
            int dstT = viewTransformation.viewOffsetY + Math.round(rootY * scale);
            out.dst.set(dstL, dstT, dstL + Math.round(w * scale), dstT + Math.round(h * scale));
        }

        return adjustRectLT''');p.write_text(s)
edit('xserver/WindowManager.java', '        parent.removeChild(window);\n    }\n\n    public void mapWindow', '        parent.removeChild(window);\n        triggerOnDestroyWindow(window);\n    }\n\n    public void mapWindow')
edit('xserver/WindowManager.java', '    private void triggerOnChangeWindowZOrder(Window window) {', '    private void triggerOnDestroyWindow(Window window) {\n        OnWindowModificationListener[] listeners = onWindowModificationListeners.snapshot();\n        for (int i = listeners.length - 1; i >= 0; i--) {\n            listeners[i].onDestroyWindow(window);\n        }\n    }\n\n    private void triggerOnChangeWindowZOrder(Window window) {')

print(candidate)
