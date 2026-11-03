#!/usr/bin/env python3
"""Run production Java resource methods with counted JNI/Android/GL boundaries.
Only native declarations and library loading are substituted. Resource decisions
and DRI3 parsers are copied verbatim from the selected production source root.
No device/GPU performance is inferred from this host regression suite.
"""
from pathlib import Path
import subprocess,sys,re,json,hashlib,os,shutil
source=Path(sys.argv[1]).resolve(); out=Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=True)
tests=Path(__file__).resolve().parent
java_home=os.environ.get('JAVA_HOME')
javac=str(Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
java=str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
if not javac or not java:raise SystemExit('Run with a JDK: set JAVA_HOME or add java/javac to PATH.')
files=['renderer/Texture.java','renderer/NativeTexture.java','renderer/GPUImage.java','renderer/AHBImage.java','xserver/Drawable.java','xserver/DrawableManager.java','xserver/extensions/DRI3Extension.java','sysvshm/SysVSharedMemory.java','xserver/SHMSegmentManager.java','xenvironment/components/XServerComponent.java']
manifest={}
for name in files:
 p=source/'app/src/main/java/com/winlator'/name;s=p.read_text();manifest[name]=hashlib.sha256(s.encode()).hexdigest()
 s=s.replace('System.loadLibrary("winlator_11");','').replace('System.loadLibrary("extras");','').replace('System.loadLibrary("ahbimage");','').replace('System.loadLibrary("winlator");','')
 def native(m):
  mods,ret,name,args=m.groups();names=[part.strip().split()[-1] for part in args.split(',') if part.strip()]
  calls={'createHardwareBuffer':f'test.Hooks.create({",".join(names)})','hardwareBufferFromSocket':f'test.Hooks.receive({",".join(names)})','lockHardwareBuffer':f'test.Hooks.lock(this, {names[0]})','unlockHardwareBuffer':f'test.Hooks.unlock({names[0]})','destroyHardwareBuffer':f'test.Hooks.destroy({",".join(names)})','nativeCloseFd':f'test.Hooks.close({names[0]})','nativeGetWidth':'32','nativeGetHeight':'16','createImageKHR':'test.Hooks.eglFails ? 0 : 999','destroyImageKHR':'test.Hooks.eglDestroyed++','ashmemCreateRegion':'test.Hooks.ashmemFails ? -1 : test.Hooks.fd()','mapSHMSegment':f'test.Hooks.map({",".join(names)})','unmapSHMSegment':f'test.Hooks.unmap({names[0]})'}
  body=calls.get(name,'false' if ret=='boolean' else 'null' if ret=='ByteBuffer' else '0')
  # The original GPU JNI destructor unconditionally unlocked the AHB.
  # Preserve that boundary behavior when executing the pre-fix source.
  if name=='destroyHardwareBuffer' and len(names)==1 and p.name=='GPUImage.java':body=f'test.Hooks.destroyLegacyGpu({names[0]})'
  if ret=='void':body=(body+';' if name in calls else '')
  else:body='return '+body+';'
  return f'{mods}{ret} {name}({args}) {{ {body} }}'
 s=re.sub(r'((?:public|private|protected)\s+(?:static\s+)?)native\s+(\w+)\s+(\w+)\(([^;]*?)\)\s*;',native,s)
 dest=out/'src/com/winlator'/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(s)
# Small boundary doubles: no production ownership/lifetime logic is duplicated.
stubs={
'androidx/annotation/Keep.java':'package androidx.annotation; public @interface Keep {}',
'android/graphics/Bitmap.java':'package android.graphics; public class Bitmap { public int getWidth(){return 32;} public int getHeight(){return 16;} }',
'android/opengl/GLES11Ext.java':'package android.opengl; public class GLES11Ext { public static final int GL_BGRA=0; }',
'android/opengl/GLES20.java':'''package android.opengl; import java.nio.Buffer; public class GLES20 {
public static final int GL_CLAMP_TO_EDGE=0,GL_LINEAR=1,GL_TEXTURE_2D=2,GL_TEXTURE_WRAP_S=3,GL_TEXTURE_WRAP_T=4,GL_TEXTURE_MAG_FILTER=5,GL_TEXTURE_MIN_FILTER=6,GL_TEXTURE0=7,GL_UNPACK_ALIGNMENT=8,GL_UNSIGNED_BYTE=9,GL_FRAMEBUFFER=10,GL_RGBA=11;
public static void glGenTextures(int n,int[] a,int off){a[off]=++test.Hooks.glId;} public static void glDeleteTextures(int n,int[] a,int off){test.Hooks.glDeletes+=n;}
public static void glTexParameteri(int a,int b,int c){} public static void glBindTexture(int a,int b){} public static void glActiveTexture(int a){} public static void glPixelStorei(int a,int b){}
public static void glTexImage2D(int a,int b,int c,int d,int e,int f,int g,int h,Buffer i){test.Hooks.uploads++;}
public static void glTexSubImage2D(int a,int b,int c,int d,int e,int f,int g,int h,Buffer i){test.Hooks.uploads++;}
public static void glBindFramebuffer(int a,int b){} public static void glCopyTexImage2D(int a,int b,int c,int d,int e,int f,int g,int h){}
}''',
'android/util/SparseArray.java':'''package android.util; import java.util.*; public class SparseArray<T> { private final TreeMap<Integer,T> map=new TreeMap<>(); public T get(int k){return map.get(k);} public void put(int k,T v){map.put(k,v);} public void remove(int k){map.remove(k);} public int indexOfKey(int k){return map.containsKey(k)?new ArrayList<>(map.keySet()).indexOf(k):-1;} public int size(){return map.size();} public int keyAt(int i){return new ArrayList<>(map.keySet()).get(i);} public T valueAt(int i){return new ArrayList<>(map.values()).get(i);} public void clear(){map.clear();} }''',
'android/system/ErrnoException.java':'package android.system; public class ErrnoException extends Exception {}',
'android/os/Build.java':'package android.os; public class Build { public static class VERSION { public static int SDK_INT=29; } public static class VERSION_CODES { public static final int O_MR1=27; } }',
'android/os/SharedMemory.java':'''package android.os; import android.system.ErrnoException; public class SharedMemory implements AutoCloseable { private final int fd=test.Hooks.fd(); public SharedMemory(){test.Hooks.shared=this;} public static SharedMemory create(String n,int s) throws ErrnoException{return new SharedMemory();} public int getFd(){return fd;} public void close(){test.Hooks.close(fd);} }''',
'android/os/ParcelFileDescriptor.java':'''package android.os; import java.io.IOException; public class ParcelFileDescriptor { private int fd=test.Hooks.fd(); public static ParcelFileDescriptor fromFd(int fd) throws IOException{return new ParcelFileDescriptor();} public int detachFd(){int v=fd;fd=-1;return v;} }''',
'com/winlator/core/Callback.java':'package com.winlator.core; public interface Callback<T> { void call(T value); }',
'com/winlator/math/Mathf.java':'package com.winlator.math; public class Mathf { public static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(v,hi));} }',
'com/winlator/xserver/Visual.java':'package com.winlator.xserver; public class Visual {}',
'com/winlator/xserver/XResource.java':'package com.winlator.xserver; public class XResource { public final int id; public XResource(int i){id=i;} }',
'com/winlator/xserver/GraphicsContext.java':'package com.winlator.xserver; public class GraphicsContext { public enum Function {COPY} }',
'com/winlator/xserver/XResourceManager.java':'package com.winlator.xserver; public class XResourceManager { public interface OnResourceLifecycleListener {default void onFreeResource(XResource r){}} }',
'com/winlator/xserver/Pixmap.java':'package com.winlator.xserver; public class Pixmap extends XResource { public final Drawable drawable; public Pixmap(Drawable d){super(d.id);drawable=d;} }',
'com/winlator/xserver/PixmapManager.java':'''package com.winlator.xserver; import java.util.*; public class PixmapManager { public Visual visual=new Visual(); private Map<Integer,Pixmap> p=new HashMap<>(); private java.util.List<XResourceManager.OnResourceLifecycleListener> listeners=new ArrayList<>(); public void addOnResourceLifecycleListener(XResourceManager.OnResourceLifecycleListener l){listeners.add(l);} public Pixmap getPixmap(int id){return p.get(id);} public Pixmap createPixmap(Drawable d){Pixmap n=new Pixmap(d);p.put(d.id,n);return n;} public Visual getVisualForDepth(byte d){return d==24||d==32?visual:null;} public void freePixmap(int id){Pixmap d=p.remove(id);for(var l:listeners)l.onFreeResource(d);} }''',
'com/winlator/xserver/Window.java':'package com.winlator.xserver; public class Window {}',
'com/winlator/xserver/WindowManager.java':'package com.winlator.xserver; public class WindowManager { public Window getWindow(int id){return id==1?new Window():null;} }',
'com/winlator/xserver/XLock.java':'package com.winlator.xserver; public interface XLock extends AutoCloseable {default void close(){}}',
'com/winlator/xserver/XServer.java':'''package com.winlator.xserver; import com.winlator.renderer.XServerRenderer; public class XServer { public enum Lockable {WINDOW_MANAGER,PIXMAP_MANAGER,DRAWABLE_MANAGER} public final PixmapManager pixmapManager=new PixmapManager(); public final DrawableManager drawableManager=new DrawableManager(this); public final WindowManager windowManager=new WindowManager(); public XLock lock(Lockable... l){return new XLock(){};} public SHMSegmentManager shm; public SHMSegmentManager getSHMSegmentManager(){return shm;} public <T> T getExtension(byte opcode){return null;} public XServerRenderer renderer=new XServerRenderer(); public XServerRenderer getRenderer(){return renderer;} }''',
'com/winlator/xserver/XClient.java':'''package com.winlator.xserver; public class XClient { public XServer xServer=new XServer(); public byte data; public int getRequestData(){return data;} public short getSequenceNumber(){return 0;} }''',
'com/winlator/xserver/XClientRequestHandler.java':'package com.winlator.xserver; public class XClientRequestHandler { public static final byte RESPONSE_CODE_SUCCESS=1; }',
'com/winlator/renderer/ASurfaceRenderer.java':'package com.winlator.renderer; public class ASurfaceRenderer {public static long getNativeContextGeneration(){return 0;} public static boolean nativePrepareCpuSourceBuffers(long a,long b,long c){return false;} public static void nativeReleaseCpuSourceBuffers(long a,long b,long c){} }',
'com/winlator/renderer/VulkanRenderer.java':'package com.winlator.renderer; public class VulkanRenderer extends XServerRenderer {}',
'com/winlator/renderer/XServerRenderer.java':'package com.winlator.renderer; public class XServerRenderer { public com.winlator.widget.XServerRendererView getRendererView(){return new com.winlator.widget.XServerRendererView();} }',
'com/winlator/widget/XServerRendererView.java':'package com.winlator.widget; public class XServerRendererView {public void queueEvent(Runnable r){r.run();}}',
'com/winlator/xenvironment/EnvironmentComponent.java':'package com.winlator.xenvironment; public abstract class EnvironmentComponent { public abstract void start(); public abstract void stop(); }',
'com/winlator/xconnector/UnixSocketConfig.java':'package com.winlator.xconnector; public class UnixSocketConfig {}',
'com/winlator/xserver/XClientConnectionHandler.java':'package com.winlator.xserver; public class XClientConnectionHandler { public XClientConnectionHandler(XServer s){} }',
'com/winlator/xserver/XClientRequestHandler.java':'package com.winlator.xserver; public class XClientRequestHandler { public static final byte RESPONSE_CODE_SUCCESS=1; }',
'com/winlator/xserver/extensions/PresentExtension.java':'package com.winlator.xserver.extensions; public class PresentExtension { public static final byte MAJOR_OPCODE=0; public void resetPacing(){} public void close(){} }',
'com/winlator/xenvironment/components/VortekRendererComponent.java':'package com.winlator.xenvironment.components; public class VortekRendererComponent {}',
'com/winlator/xconnector/XConnectorEpoll.java':'package com.winlator.xconnector; public class XConnectorEpoll {public XConnectorEpoll(UnixSocketConfig c,Object h,Object r){} public void start(){} public void stop(){test.Hooks.connectorStopped=true;} public void setInitialInputBufferCapacity(int n){} public void setCanReceiveAncillaryMessages(boolean n){} public static void closeFd(int fd){test.Hooks.close(fd);}}',
'com/winlator/xconnector/XStreamLock.java':'package com.winlator.xconnector; public interface XStreamLock extends AutoCloseable {default void close(){}}',
'com/winlator/xconnector/XOutputStream.java':'package com.winlator.xconnector; public class XOutputStream {public XStreamLock lock(){return new XStreamLock(){};} public void writeByte(byte v){}public void writeShort(short v){}public void writeInt(int v){}public void writePad(int v){}}',
'com/winlator/xconnector/XInputStream.java':'''package com.winlator.xconnector; import java.nio.*; public class XInputStream { public ByteBuffer b; public int fd; public int readInt(){return b.getInt();} public short readShort(){return b.getShort();} public byte readByte(){return b.get();} public long readLong(){return b.getLong();} public void skip(int n){b.position(b.position()+n);} public int getAncillaryFd(){int v=fd;fd=-1;return v;} }''',
'com/winlator/xserver/extensions/Extension.java':'''package com.winlator.xserver.extensions; import com.winlator.xserver.*; import com.winlator.xconnector.*; import com.winlator.xserver.errors.*; import java.io.*; public interface Extension { String getName(); byte getMajorOpcode(); int getNumEvents();int getNumErrors();void setFirstEventId(byte id);void setFirstErrorId(byte id);byte getFirstEventId();byte getFirstErrorId();void handleRequest(XClient c,XInputStream i,XOutputStream o)throws IOException,XRequestError;}''',
}
for error in ['XRequestError','BadAlloc','BadDrawable','BadIdChoice','BadImplementation','BadWindow','BadValue','BadMatch']:
 base='Exception' if error=='XRequestError' else 'XRequestError'
 stubs[f'com/winlator/xserver/errors/{error}.java']=f'package com.winlator.xserver.errors; public class {error} extends {base} {{ public {error}(){{}} public {error}(int id){{}} }}'
for name,text in stubs.items():
 p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
for p in tests.glob('*.java'):
 dest=out/'src/test'/p.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(p.read_bytes())
(out/'classes').mkdir(exist_ok=True)
subprocess.run([javac,'-d',str(out/'classes'),*[str(p) for p in (out/'src').rglob('*.java')]],check=True,capture_output=False)
results=[]
for case in range(28):
 r=subprocess.run([java,'-cp',str(out/'classes'),'test.ResourceRegression',str(case)],capture_output=True,text=True,timeout=10)
 results.append(dict(case=case,exit=r.returncode,stdout=r.stdout,stderr=r.stderr))
(out/'result.json').write_text(json.dumps(dict(productionSourceHashes=manifest,results=results),indent=2)+'\n')
print(json.dumps(dict(passed=sum(r['exit']==0 for r in results),total=len(results),failures=[r['case'] for r in results if r['exit']])))

if "--baseline" not in sys.argv[3:] and any(r["exit"] for r in results):raise SystemExit(1)
