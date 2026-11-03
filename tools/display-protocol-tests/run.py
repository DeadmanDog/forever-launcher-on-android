#!/usr/bin/env python3
"""Execute complete production Java classes; replace only Android/GL/JNI boundaries."""
from pathlib import Path
import sys,os,subprocess,json,hashlib,re,shutil
root=Path(sys.argv[1]).resolve();out=Path(sys.argv[2]).resolve();out.mkdir(parents=True,exist_ok=False)
tests=Path(__file__).resolve().parent
files=['renderer/Texture.java','renderer/RenderTarget.java','renderer/VertexAttribute.java','renderer/EffectComposer.java','renderer/material/ShaderMaterial.java','renderer/effects/Effect.java','renderer/effects/RenderScaleEffect.java','renderer/effects/SourceTextureFilterEffect.java','renderer/ViewTransformation.java','renderer/XServerRenderer.java','renderer/ASurfaceRenderer.java','xserver/XClient.java','xserver/ListenerSnapshot.java','xserver/Bitmask.java','xserver/EventListener.java','xserver/extensions/MITSHMExtension.java','xserver/events/Event.java']
optional='xserver/events/ShmCompletionNotify.java'
if (root/'app/src/main/java/com/winlator'/optional).exists():files.append(optional)
hashes={}
for name in files:
 p=root/'app/src/main/java/com/winlator'/name;s=p.read_text();hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest();s=re.sub(r'System.loadLibrary\("[^\"]+"\);','',s)
 def native(m):
  mods,ret,name,args=m.groups();params=[v.strip().split()[-1] for v in args.split(',') if v.strip()]
  if name=='nativeUpdateWindow':body='test.Hooks.windowUpdate('+','.join(params)+');'
  elif ret=='void':body=''
  else:body='return '+('true' if ret=='boolean' else '0')+';'
  return f'{mods}{ret} {name}({args}) {{ {body} }}'
 s=re.sub(r'((?:public|private|protected)\s+(?:static\s+)?)native\s+(\w+)\s+(\w+)\(([^;]*?)\)\s*;',native,s)
 dest=out/'src/com/winlator'/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(s)
stubs={
'androidx/annotation/Keep.java':'package androidx.annotation;public @interface Keep{}',
'androidx/annotation/NonNull.java':'package androidx.annotation;public @interface NonNull{}',
'androidx/collection/ArrayMap.java':'''package androidx.collection;import java.util.*;public class ArrayMap<K,V> extends LinkedHashMap<K,V>{public K keyAt(int i){return new ArrayList<>(keySet()).get(i);}public V valueAt(int i){return get(keyAt(i));}public V removeAt(int i){return remove(keyAt(i));}public V setValueAt(int i,V v){return put(keyAt(i),v);}}''',
'android/graphics/Rect.java':'''package android.graphics;public class Rect{public int left,top,right,bottom;public void set(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public void set(Rect r){set(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}public boolean equals(Object o){if(!(o instanceof Rect r))return false;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}''',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap{}',
'android/graphics/BitmapFactory.java':'package android.graphics;public class BitmapFactory{public static class Options{public boolean inScaled,inPremultiplied;}public static Bitmap decodeResource(Object r,int id,Options o){return null;}}',
'android/content/Context.java':'package android.content;public class Context{public Object getResources(){return null;}}',
'android/view/Surface.java':'package android.view;public class Surface{}',
'app/gamenative/R.java':'package app.gamenative;public class R{public static class drawable{public static int cursor=1;}}',
'android/opengl/GLES11Ext.java':'package android.opengl;public class GLES11Ext{public static final int GL_BGRA=20;}',
'com/winlator/widget/FrameRating.java':'package com.winlator.widget;public class FrameRating{public void update(){}}',
'com/winlator/widget/XServerRendererView.java':'package com.winlator.widget;public class XServerRendererView{public void queueEvent(Runnable r){r.run();}public void requestRender(){}}',
'com/winlator/widget/XServerView.java':'package com.winlator.widget;public class XServerView extends XServerRendererView{public android.content.Context getContext(){return new android.content.Context();}}',
'com/winlator/renderer/GLRenderer.java':'''package com.winlator.renderer;public class GLRenderer{public final com.winlator.widget.XServerView xServerView=new com.winlator.widget.XServerView();private final VertexAttribute quad=new VertexAttribute("position",2);public int overrideW,overrideH;public boolean failDraw;public GLRenderer(){quad.put(new float[]{0,0,1,1});}public int getSurfaceWidth(){return 800;}public int getSurfaceHeight(){return 600;}public VertexAttribute getQuadVertices(){return quad;}public void setViewportNeedsUpdate(boolean v){}public void setRenderTargetSizeOverride(int w,int h){overrideW=w;overrideH=h;}public void clearRenderTargetSizeOverride(){overrideW=overrideH=0;}public void drawScene(){if(failDraw)throw new IllegalStateException("injected draw error");}}''',
'com/winlator/renderer/GPUImage.java':'package com.winlator.renderer;public class GPUImage extends Texture{}',
'com/winlator/renderer/AHBImage.java':'''package com.winlator.renderer;public class AHBImage extends Texture{public AHBImage(short w,short h){}public int getWidth(){return 32;}public int getHeight(){return 16;}public long getHardwareBufferPtr(){return 1;}public long getScanoutHardwareBufferPtr(){return 1;}public void prepareScanoutSources(){}public int consumeAcquireFence(){return -1;}public int getLastUsedSlot(){return 0;}}''',
'com/winlator/xserver/XResource.java':'package com.winlator.xserver;public class XResource{public final int id;public XResource(int i){id=i;}}',
'com/winlator/xserver/XResourceManager.java':'package com.winlator.xserver;public class XResourceManager{public interface OnResourceLifecycleListener{void onFreeResource(XResource r);}public void addOnResourceLifecycleListener(OnResourceLifecycleListener l){}public void removeOnResourceLifecycleListener(OnResourceLifecycleListener l){}}',
'com/winlator/xserver/XLock.java':'package com.winlator.xserver;public interface XLock extends AutoCloseable{default void close(){}}',
'com/winlator/xserver/Drawable.java':'''package com.winlator.xserver;import java.nio.*;import com.winlator.renderer.*;public class Drawable{public final Object renderLock=new Object();public short width=32,height=16;public int id=1;public ByteBuffer data=ByteBuffer.allocateDirect(64);public Texture texture=new Texture();public ByteBuffer getData(){return data;}public ByteBuffer getBuffer(){return data;}public Texture getTexture(){return texture;}public void setTexture(Texture t){texture=t;}public void setOnDrawListener(Runnable r){}public static Drawable fromBitmap(android.graphics.Bitmap b){return null;}public void drawImage(short a,short b,short c,short d,short e,short f,byte depth,ByteBuffer bytes,short w,short h){test.Hooks.blits++;test.Hooks.firstPixel=bytes.getInt(0);}}''',
'com/winlator/xserver/DrawableManager.java':'package com.winlator.xserver;public class DrawableManager{public Drawable drawable=new Drawable();public Drawable getDrawable(int i){return drawable;}public void removeDrawable(int id){}}',
'com/winlator/xserver/GraphicsContext.java':'package com.winlator.xserver;public class GraphicsContext extends XResource{public GraphicsContext(){super(1);}public enum Function{COPY,XOR}public Function getFunction(){return Function.COPY;}}',
'com/winlator/xserver/GraphicsContextManager.java':'package com.winlator.xserver;public class GraphicsContextManager extends XResourceManager{public GraphicsContext getGraphicsContext(int i){return new GraphicsContext();}public void freeGraphicsContext(int i){}}',
'com/winlator/xserver/SHMSegmentManager.java':'package com.winlator.xserver;import java.nio.*;public class SHMSegmentManager{public ByteBuffer data=ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);public void attach(int x,int id){}public void detach(int x){}public ByteBuffer getData(int id){return data;}}',
'com/winlator/xserver/WindowAttributes.java':'package com.winlator.xserver;public class WindowAttributes{public static final int FLAG_CURSOR=1;public boolean mapped=true;public void setMapped(boolean v){mapped=v;}public boolean isMapped(){return mapped;}public boolean isOverrideRedirect(){return false;}public Cursor getCursor(){return null;}}',
'com/winlator/xserver/Window.java':'''package com.winlator.xserver;import java.util.*;public class Window extends XResource{public final WindowAttributes attributes=new WindowAttributes();public Drawable content=new Drawable();public String klass="wow.exe";public int x,y;public Window parent;public final List<Window> children=new ArrayList<>();public final List<EventListener> listeners=new ArrayList<>();public Window(int id){super(id);}public Drawable getContent(){return content;}public Window getParent(){return parent;}public List<Window> getChildren(){return children;}public int getX(){return x;}public int getY(){return y;}public String getClassName(){return klass;}public void addEventListener(EventListener l){listeners.add(l);}public void removeEventListener(EventListener l){listeners.remove(l);}public void removeChild(Window c){children.remove(c);c.parent=null;}public boolean isInputOutput(){return content!=null;}public void sendEvent(int id,com.winlator.xserver.events.Event e){}}''',
'com/winlator/xserver/WindowManager.java':'''package com.winlator.xserver;public class WindowManager extends XResourceManager{public final Window rootWindow=new Window(1);public void destroyWindow(int id){}public void addOnWindowModificationListener(OnWindowModificationListener l){}public interface OnWindowModificationListener{default void onMapWindow(Window w){}default void onUnmapWindow(Window w){}default void onDestroyWindow(Window w){}default void onChangeWindowZOrder(Window w){}default void onUpdateWindowGeometry(Window w,boolean r){}default void onUpdateWindowAttributes(Window w,Bitmask m){}default void onUpdateWindowContent(Window w){}}}''',
'com/winlator/xserver/Cursor.java':'package com.winlator.xserver;public class Cursor extends XResource{public Cursor(){super(1);}public Drawable cursorImage;public int hotSpotX,hotSpotY;public boolean isVisible(){return true;}}',
'com/winlator/xserver/CursorManager.java':'package com.winlator.xserver;public class CursorManager extends XResourceManager{public void freeCursor(int id){}}',
'com/winlator/xserver/Pixmap.java':'package com.winlator.xserver;public class Pixmap extends XResource{public Pixmap(){super(1);}}',
'com/winlator/xserver/PixmapManager.java':'package com.winlator.xserver;public class PixmapManager extends XResourceManager{public void freePixmap(int id){}}',
'com/winlator/xserver/Pointer.java':'package com.winlator.xserver;public class Pointer{public void addOnPointerMotionListener(OnPointerMotionListener l){}public interface OnPointerMotionListener{void onPointerMove(short x,short y);}}',
'com/winlator/xserver/ResourceIDs.java':'package com.winlator.xserver;public class ResourceIDs{public Integer get(){return 1;}public void free(Integer b){}public boolean isInInterval(int i,Integer b){return true;}}',
'com/winlator/xserver/XServer.java':'''package com.winlator.xserver;public class XServer{public static final java.nio.charset.Charset LATIN1_CHARSET=java.nio.charset.StandardCharsets.ISO_8859_1;public final WindowManager windowManager=new WindowManager();public final DrawableManager drawableManager=new DrawableManager();public final PixmapManager pixmapManager=new PixmapManager();public final GraphicsContextManager graphicsContextManager=new GraphicsContextManager();public final CursorManager cursorManager=new CursorManager();public final ResourceIDs resourceIDs=new ResourceIDs();public final Pointer pointer=new Pointer();public final ScreenInfo screenInfo=new ScreenInfo();public static class ScreenInfo{public short width=1792,height=810;}public final InputDeviceManager inputDeviceManager=new InputDeviceManager();public static class InputDeviceManager{public Window getPointWindow(){return null;}}public final SHMSegmentManager shm=new SHMSegmentManager();public SHMSegmentManager getSHMSegmentManager(){return shm;}public enum Lockable{WINDOW_MANAGER,DRAWABLE_MANAGER,GRAPHIC_CONTEXT_MANAGER,SHMSEGMENT_MANAGER}public XLock lock(Lockable...l){return new XLock(){};}public XLock lockAll(){return lock();}public <T>T getExtension(byte b){return null;}}''',
'com/winlator/xserver/ShmFramePacer.java':'package com.winlator.xserver;public class ShmFramePacer{public static long framePresented(int id){return 0;}}',
'com/winlator/xserver/XClientRequestHandler.java':'package com.winlator.xserver;public class XClientRequestHandler{public static final byte RESPONSE_CODE_SUCCESS=1;}',
'com/winlator/xserver/extensions/SyncExtension.java':'package com.winlator.xserver.extensions;public class SyncExtension{public static final byte MAJOR_OPCODE=0;public void onClientDisconnected(com.winlator.xserver.XClient c){}}',
'com/winlator/xserver/extensions/PresentExtension.java':'package com.winlator.xserver.extensions;public class PresentExtension{public static final byte MAJOR_OPCODE=0;public void onClientDisconnected(com.winlator.xserver.XClient c){}public void onWindowDestroyed(com.winlator.xserver.Window w){}}',
'com/winlator/xserver/extensions/XInput2Extension.java':'package com.winlator.xserver.extensions;public class XInput2Extension{public static final byte MAJOR_OPCODE=0;public void onClientDisconnected(com.winlator.xserver.XClient c){}}',
'com/winlator/xserver/extensions/Extension.java':'package com.winlator.xserver.extensions;import com.winlator.xserver.*;import com.winlator.xconnector.*;import com.winlator.xserver.errors.*;public interface Extension{String getName();byte getMajorOpcode();int getNumEvents();int getNumErrors();void setFirstEventId(byte b);void setFirstErrorId(byte b);byte getFirstEventId();byte getFirstErrorId();void handleRequest(XClient c,XInputStream i,XOutputStream o)throws java.io.IOException,XRequestError;}',
'com/winlator/xconnector/XStreamLock.java':'package com.winlator.xconnector;public interface XStreamLock extends AutoCloseable{default void close()throws java.io.IOException{}}',
'com/winlator/xconnector/Client.java':'package com.winlator.xconnector;public class Client{public Client getConnector(){return this;}public void pauseClientReads(Client c,long n){}}',
'com/winlator/xconnector/XInputStream.java':'package com.winlator.xconnector;import java.nio.*;public class XInputStream{public ByteBuffer b=ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);public int available(){return b.remaining();}public int readInt(){return b.getInt();}public long readUnsignedInt(){return Integer.toUnsignedLong(b.getInt());}public short readShort(){return b.getShort();}public int readUnsignedShort(){return Short.toUnsignedInt(b.getShort());}public byte readByte(){return b.get();}public int readUnsignedByte(){return Byte.toUnsignedInt(b.get());}public void skip(int n){b.position(b.position()+n);}}',
'com/winlator/xconnector/XOutputStream.java':'package com.winlator.xconnector;import java.nio.*;public class XOutputStream{public ByteBuffer b=ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);public XStreamLock lock(){return new XStreamLock(){};}public void writeByte(byte v){b.put(v);}public void writeShort(short v){b.putShort(v);}public void writeInt(int v){b.putInt(v);}public void writePad(int n){for(int i=0;i<n;i++)b.put((byte)0);}}',
}
for event in ['DestroyNotify','UnmapNotify']:
 stubs[f'com/winlator/xserver/events/{event}.java']=f'package com.winlator.xserver.events;public class {event} extends Event{{public {event}(com.winlator.xserver.Window a,com.winlator.xserver.Window b){{super(0);}}public {event}(com.winlator.xserver.Window a,com.winlator.xserver.Window b,boolean c){{super(0);}}public void send(short s,com.winlator.xconnector.XOutputStream o){{}}}}'
for err in ['XRequestError' ,'BadDrawable','BadGraphicsContext','BadImplementation','BadSHMSegment','BadValue','BadMatch','BadAccess']:
 base='Exception' if err=='XRequestError' else 'XRequestError';stubs[f'com/winlator/xserver/errors/{err}.java']=f'package com.winlator.xserver.errors;public class {err} extends {base}{{public {err}(){{}}public {err}(int i){{}}}}'
for name,s in stubs.items():
 p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
# Run complete production window lifecycle methods with model-only surrounding state.
wm=root/'app/src/main/java/com/winlator/xserver/WindowManager.java';text=wm.read_text();hashes['xserver/WindowManager.java']=hashlib.sha256(wm.read_bytes()).hexdigest()
def method(marker):
 start=text.index(marker);b=text.index('{',start);depth=1;i=b+1
 while depth:
  depth += (text[i]=='{')-(text[i]=='}');i+=1
 return text[start:i]
methods=[method(marker) for marker in ['public void destroyWindow(', 'private void removeAllSubwindowsAndWindow(', 'public void unmapWindow(', 'private void triggerOnUnmapWindow(']]
if 'private void triggerOnDestroyWindow(' in text:methods.append(method('private void triggerOnDestroyWindow('))
fixture=(tests/'WindowDestroyRegression.java').read_text().replace('/* PRODUCTION */','\n'.join(methods))
p=out/'src/com/winlator/xserver/WindowDestroyRegression.java';p.write_text(fixture)
for p in tests.glob('*.java'):
 if p.name=='WindowDestroyRegression.java':continue
 dest=out/('src/android/opengl' if p.name=='GLES20.java' else 'src/test')/p.name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(p.read_bytes())
(out/'classes').mkdir();jdk=Path(os.environ.get('JAVA_HOME','/nonexistent'))/'bin';javac=str(jdk/'javac') if (jdk/'javac').exists() else shutil.which('javac');java=str(jdk/'java') if (jdk/'java').exists() else shutil.which('java')
subprocess.run([javac,'-d',str(out/'classes'),*[str(p) for p in (out/'src').rglob('*.java')]],check=True)
results=[]
for case in range(37):
 r=subprocess.run([java,'-cp',str(out/'classes'),'test.Regression',str(case)],capture_output=True,text=True,timeout=10);results.append(dict(case=case,exit=r.returncode,stdout=r.stdout,stderr=r.stderr))
for mode in range(5):
 r=subprocess.run([java,'-cp',str(out/'classes'),'com.winlator.xserver.WindowDestroyRegression',str(mode)],capture_output=True,text=True,timeout=10);results.append(dict(case='destroy-'+str(mode),exit=r.returncode,stdout=r.stdout,stderr=r.stderr))
(out/'result.json').write_text(json.dumps(dict(sourceHashes=hashes,results=results),indent=2)+'\n');print(json.dumps(dict(passed=sum(r['exit']==0 for r in results),total=len(results),failures=[r['case'] for r in results if r['exit']])))
if '--baseline' not in sys.argv[3:] and any(r['exit'] for r in results):raise SystemExit(1)
