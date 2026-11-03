package test;
import com.winlator.renderer.*;
import com.winlator.renderer.effects.*;
import com.winlator.renderer.material.*;
import com.winlator.xserver.*;
import com.winlator.xserver.extensions.*;
import com.winlator.xconnector.*;
import android.opengl.GLES20;
import java.nio.*;
import java.util.*;
public class Regression {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static class Material extends ShaderMaterial{
  Material(){setUniformNames("value");}
  protected String getVertexShader(){return "void main() { }";}
  protected String getFragmentShader(){return "void main() { }";}
 }
 static class Pass extends Effect {protected ShaderMaterial createMaterial(){return new Material();}}
 static class Scale extends Pass implements RenderScaleEffect{public int getRenderWidth(GLRenderer r,int w){return w/2;}public int getRenderHeight(GLRenderer r,int h){return h/2;}}
 static XClient client(XServer server,XInputStream in,XOutputStream out){return new XClient(server,in,out);}
 static void shm(int offset,boolean event,int format,int bytes,int srcX)throws Exception{
  XServer s=new XServer();s.shm.data=ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);if(bytes>=20){s.shm.data.putInt(0,111);s.shm.data.putInt(16,222);}s.shm.data.position(Math.min(3,bytes));
  XInputStream in=new XInputStream();in.b.putInt(1).putInt(1).putShort((short)2).putShort((short)2).putShort((short)srcX).putShort((short)0).putShort((short)2).putShort((short)2).putShort((short)0).putShort((short)0).put((byte)24).put((byte)format).put((byte)(event?1:0)).put((byte)0).putInt(1).putInt(offset).flip();
  XOutputStream out=new XOutputStream();XClient c=client(s,in,out);c.setRequestData((byte)3);MITSHMExtension ext=new MITSHMExtension();ext.setFirstEventId((byte)72);ext.handleRequest(c,in,out);
  check(s.shm.data.position()==Math.min(3,bytes),"SHM owner buffer position changed");
  if(offset==16)check(Hooks.firstPixel==222,"nonzero SHM offset ignored: "+Hooks.firstPixel);
  if(event){check(out.b.position()==32,"missing or malformed completion event: "+out.b.position());check(out.b.get(0)==72,"wrong dynamic event base");check(out.b.getShort(8)==3&&out.b.get(10)==MITSHMExtension.MAJOR_OPCODE,"wrong completion opcodes");check(out.b.getInt(16)==offset,"wrong completion offset");}else check(out.b.position()==0,"unsolicited completion event");
 }
 static ASurfaceRenderer surface(XServer s){ASurfaceRenderer r=new ASurfaceRenderer(new com.winlator.widget.XServerView(),s);r.onSurfaceChanged(2688,1216);return r;}
 static Window window(XServer s,String klass){Window w=new Window(2);w.klass=klass;w.parent=s.windowManager.rootWindow;w.content.width=800;w.content.height=600;w.x=100;w.y=20;s.windowManager.rootWindow.children.add(w);return w;}
 public static void main(String[]args)throws Exception{
  int c=Integer.parseInt(args[0]);
  switch(c){
   case 0:{Texture t=new Texture();t.allocateTexture((short)32,(short)16,ByteBuffer.allocateDirect(2048));Hooks.params=0;t.setFilters(GLES20.GL_LINEAR,GLES20.GL_LINEAR);check(Hooks.params==0,"unchanged filters submit redundant GL params="+Hooks.params);break;}
   case 1:{VertexAttribute v=new VertexAttribute("position",2);v.put(new float[]{1,2});v.bind(11);v.disable();v.bind(12);check(v.getLocation()==112,"attribute location reused across programs");break;}
   case 2:{Material m=new Material();m.use();m.destroy();m.use();m.setUniformInt("value",1);check(Hooks.lastUniform==m.programId+200,"uniform location reused after program destroy");break;}
   case 3:case 14:{Hooks.failShaderType=c==3?GLES20.GL_VERTEX_SHADER:GLES20.GL_FRAGMENT_SHADER;boolean thrown=false;try{new Material().use();}catch(RuntimeException e){thrown=true;}check(thrown,"compile error accepted");check(GLES20.programs.isEmpty()&&GLES20.shaders.isEmpty(),"shader compile failure leaks GL resources");break;}
   case 4:{Hooks.failLink=true;boolean thrown=false;try{new Material().use();}catch(RuntimeException e){thrown=true;}check(thrown,"link error accepted as successful program");check(GLES20.programs.isEmpty()&&GLES20.shaders.isEmpty(),"link failure leaks resources");break;}
   case 5:case 15:case 16:{int n=c==5?1:c==15?2:3;GLRenderer r=new GLRenderer();EffectComposer ec=new EffectComposer(r);List<Effect> list=new ArrayList<>();for(int i=0;i<n;i++)list.add(new Pass());ec.setEffects(list);ec.render();check(Hooks.framebuffers==n,"unused full-size framebuffers allocated="+Hooks.framebuffers+" for "+n+" effects");break;}
   case 6:{GLRenderer r=new GLRenderer();r.failDraw=true;EffectComposer ec=new EffectComposer(r);ec.setEffects(List.of(new Scale()));boolean thrown=false;try{ec.render();}catch(IllegalStateException e){thrown=true;}check(thrown,"fault not injected");check(r.overrideW==0&&r.overrideH==0&&Hooks.boundFramebuffer==0,"render failure leaves scaled target/FBO active");break;}
   case 7:{XClient x=client(new XServer(),new XInputStream(),new XOutputStream());Window w=new Window(2);x.setEventListenerForWindow(w,new Bitmask(4));x.setEventListenerForWindow(w,new Bitmask());check(!x.isInterestedIn(4,w)&&x.getEventMaskForWindow(w).isEmpty(),"cleared event mask remains subscribed");check(w.listeners.isEmpty(),"window listener retained");break;}
   case 8:{XInputStream in=new XInputStream();XOutputStream out=new XOutputStream();XClient x=client(new XServer(),in,out);new MITSHMExtension().handleRequest(x,in,out);check(out.b.position()==32,"QueryVersion reply bytes="+out.b.position()+"; expected 32");break;}
   case 9:{shm(16,false,2,64,0);break;}
   case 10:{shm(0,true,2,64,0);break;}
   case 11:{boolean rejected=false;try{shm(60,false,2,64,0);}catch(com.winlator.xserver.errors.XRequestError e){rejected=true;}check(rejected&&Hooks.blits==0,"out-of-range SHM image dispatched to native blit");break;}
   case 12:{XServer s=new XServer();Window w=window(s,"wow.exe");ASurfaceRenderer r=surface(s);r.updateScene();check(Boolean.TRUE.equals(Hooks.visible.get(2L)),"standalone mapped game window has no destination geometry");int[]g=Hooks.geometry.get(2L);check(g[6]>g[4]&&g[7]>g[5],"invalid destination");break;}
   case 13:{XServer s=new XServer();Window w=window(s,"explorer.exe");w.x=w.y=0;ASurfaceRenderer r=surface(s);r.updateScene();check(Boolean.TRUE.equals(Hooks.visible.get(2L)),"setup must be visible");r.setUnviewableWMClasses("explorer.exe");r.updateScene();check(Boolean.FALSE.equals(Hooks.visible.get(2L)),"filtered window stays visible/composited");break;}
   case 17:{Hooks.missingAttribute=true;VertexAttribute v=new VertexAttribute("inactive",2);v.put(new float[]{1,2});v.bind(1);check(Hooks.attributeEnables==0,"inactive -1 attribute sent to GL");break;}
   case 18:{Material m=new Material();m.use();m.use();check(Hooks.uniformQueries==1,"unchanged material re-queries uniform");m.destroy();check(GLES20.programs.isEmpty()&&GLES20.shaders.isEmpty(),"success destroy leak");break;}
   case 19:{Texture t=new Texture();t.setFilters(GLES20.GL_NEAREST,GLES20.GL_NEAREST);t.allocateTexture((short)32,(short)16,ByteBuffer.allocateDirect(2048));Hooks.params=0;t.setFilters(GLES20.GL_LINEAR,GLES20.GL_LINEAR);check(Hooks.params>0,"changed filters not applied");break;}
   case 20:{shm(0,false,2,64,0);check(Hooks.firstPixel==111&&Hooks.blits==1,"zero offset regression");break;}
   case 21:{boolean rejected=false;try{shm(0,false,2,64,1);}catch(com.winlator.xserver.errors.XRequestError e){rejected=true;}check(rejected&&Hooks.blits==0,"source rectangle outside image accepted");break;}
   case 22:{XServer s=new XServer();Window w=window(s,"explorer.exe");w.x=w.y=0;ASurfaceRenderer r=surface(s);r.updateScene();check(Boolean.TRUE.equals(Hooks.visible.get(2L)),"normal desktop must remain visible");w.attributes.mapped=false;r.updateScene();check(Boolean.FALSE.equals(Hooks.visible.get(2L)),"unmapped window remains visible");break;}
   case 23:{GLRenderer r=new GLRenderer();EffectComposer ec=new EffectComposer(r);ec.setEffects(List.of(new Scale()));ec.render();check(r.overrideW==0&&Hooks.boundFramebuffer==0,"normal scale restore failed");ec.render();check(Hooks.framebuffers==1,"steady scale frame reallocates targets");break;}
   case 36:{GLRenderer r=new GLRenderer();EffectComposer ec=new EffectComposer(r);ec.setEffects(List.of(new Pass()));for(int i=0;i<1000;i++)ec.render();System.out.println("WORK params="+Hooks.params+" framebuffers="+Hooks.framebuffers+" uniformQueries="+Hooks.uniformQueries);break;}
   case 24:{Hooks.missingUniform=true;Material m=new Material();m.use();m.use();check(Hooks.uniformQueries==1,"inactive uniform queried every draw="+Hooks.uniformQueries);break;}
   case 25:{EffectComposer ec=new EffectComposer(new GLRenderer());ec.render();check(Hooks.framebuffers==0,"empty effects allocate render targets");break;}
   case 26:{VertexAttribute v=new VertexAttribute("position",2);v.put(new float[]{1,2});v.bind(1);v.disable();v.bind(1);check(Hooks.attribQueries==1,"same-program attribute cache lost");break;}
   case 27:{VertexAttribute v=new VertexAttribute("position",2);v.put(new float[]{1,2});v.bind(1);v.disable();v.bind(2);v.disable();v.bind(1);check(v.getLocation()==101,"switch back uses other program attribute");break;}
   case 28:{EffectComposer ec=new EffectComposer(new GLRenderer());ec.setEffects(List.of(new Pass(),new Pass(),new Pass()));ec.render();ec.setEffects(List.of(new Pass()));ec.render();check(Hooks.framebufferDeletes==2,"unused ping-pong targets retained after chain shrink="+Hooks.framebufferDeletes);break;}
   case 29:{shm(16,true,2,32,0);check(Hooks.blits==1,"exact-end SHM image rejected");break;}
   case 30:{boolean rejected=false;try{shm(-4,false,2,64,0);}catch(com.winlator.xserver.errors.XRequestError e){rejected=true;}check(rejected&&Hooks.blits==0,"unsigned huge offset dispatched");break;}
   case 31:{boolean rejected=false;try{shm(0,false,1,64,0);}catch(com.winlator.xserver.errors.XRequestError e){rejected=true;}check(rejected&&Hooks.blits==0,"unsupported planar data dispatched as packed pixels");break;}
   case 32:{XServer s=new XServer();Window w=window(s,"explorer.exe");w.x=w.y=0;ASurfaceRenderer r=surface(s);r.updateScene();w.content.width=0;w.content.height=0;r.updateScene();check(Boolean.FALSE.equals(Hooks.visible.get(2L)),"invalid geometry leaves old surface visible");break;}
   case 33:{XServer s=new XServer();Window w=window(s,"explorer.exe");w.x=w.y=0;ASurfaceRenderer r=surface(s);r.updateScene();r.setUnviewableWMClasses("explorer.exe");r.updateScene();r.setUnviewableWMClasses();r.updateScene();check(Boolean.TRUE.equals(Hooks.visible.get(2L)),"unfiltered surface not restored");break;}
   case 34:{Hooks.missingUniform=true;Material m=new Material();m.use();m.setUniformFloat("value",1);check(Hooks.lastUniform==0,"inactive uniform sent to GL");m.destroy();m.use();check(Hooks.uniformQueries==2,"new program did not re-query inactive location");break;}
   case 35:{Hooks.missingAttribute=true;VertexAttribute v=new VertexAttribute("unused",2);v.put(new float[]{1,2});v.bind(1);v.bind(1);check(Hooks.attribQueries==1&&Hooks.attributeEnables==0,"inactive attribute not cached/skipped");break;}

  }
  System.out.println("PASS "+c);
 }
}
