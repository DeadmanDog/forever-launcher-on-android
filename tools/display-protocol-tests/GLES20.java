package android.opengl;
import java.nio.Buffer;
import java.util.*;
import test.Hooks;
public class GLES20 {
 public static final int GL_CLAMP_TO_EDGE=1,GL_LINEAR=2,GL_NEAREST=3,GL_TEXTURE_2D=4,GL_TEXTURE_WRAP_S=5,GL_TEXTURE_WRAP_T=6,GL_TEXTURE_MAG_FILTER=7,GL_TEXTURE_MIN_FILTER=8,GL_TEXTURE0=9,GL_UNPACK_ALIGNMENT=10,GL_UNSIGNED_BYTE=11,GL_FRAMEBUFFER=12,GL_RGBA=13,GL_FRAMEBUFFER_COMPLETE=14,GL_COLOR_ATTACHMENT0=15,GL_ARRAY_BUFFER=16,GL_STATIC_DRAW=17,GL_FLOAT=18,GL_VERTEX_SHADER=19,GL_FRAGMENT_SHADER=20,GL_COMPILE_STATUS=21,GL_LINK_STATUS=22,GL_COLOR_BUFFER_BIT=23,GL_TRIANGLE_STRIP=24;
 public static final Set<Integer> programs=new HashSet<>(),shaders=new HashSet<>();
 public static final Map<Integer,Integer> types=new HashMap<>();
 public static void glGenTextures(int n,int[] a,int off){a[off]=++Hooks.id;}
 public static void glDeleteTextures(int n,int[] a,int off){}
 public static void glBindTexture(int t,int id){}
 public static void glTexParameteri(int t,int p,int v){Hooks.params++;}
 public static void glActiveTexture(int t){}
 public static void glPixelStorei(int p,int v){}
 public static void glTexImage2D(int t,int l,int f,int w,int h,int b,int a,int u,Buffer data){}
 public static void glTexSubImage2D(int t,int l,int x,int y,int w,int h,int f,int u,Buffer data){}
 public static void glCopyTexImage2D(int t,int l,int f,int x,int y,int w,int h,int b){}
 public static void glGenFramebuffers(int n,int[] a,int off){a[off]=++Hooks.id;Hooks.framebuffers++;}
 public static void glBindFramebuffer(int t,int id){Hooks.boundFramebuffer=id;}
 public static void glDeleteFramebuffers(int n,int[] a,int off){Hooks.framebufferDeletes+=n;}
 public static void glFramebufferTexture2D(int t,int a,int tt,int id,int l){}
 public static int glCheckFramebufferStatus(int t){return GL_FRAMEBUFFER_COMPLETE;}
 public static void glGenBuffers(int n,int[] a,int off){a[off]=++Hooks.id;}
 public static void glDeleteBuffers(int n,int[] a,int off){}
 public static void glBindBuffer(int t,int id){}
 public static void glBufferData(int t,int n,Buffer b,int usage){}
 public static int glGetAttribLocation(int program,String name){Hooks.attribQueries++;return Hooks.missingAttribute?-1:program+100;}
 public static void glEnableVertexAttribArray(int l){Hooks.enabledAttribute=l;Hooks.attributeEnables++;}
 public static void glDisableVertexAttribArray(int l){}
 public static void glVertexAttribPointer(int l,int size,int t,boolean n,int stride,int offset){}
 public static int glCreateProgram(){int id=++Hooks.id;programs.add(id);return id;}
 public static int glCreateShader(int t){int id=++Hooks.id;shaders.add(id);types.put(id,t);return id;}
 public static void glShaderSource(int id,String s){}
 public static void glCompileShader(int id){}
 public static void glGetShaderiv(int id,int p,int[] a,int off){a[off]=Hooks.failShaderType==types.get(id)?0:1;}
 public static String glGetShaderInfoLog(int id){return "injected compiler error";}
 public static void glAttachShader(int p,int s){}
 public static void glLinkProgram(int p){}
 public static void glGetProgramiv(int id,int p,int[] a,int off){a[off]=Hooks.failLink?0:1;}
 public static String glGetProgramInfoLog(int id){return "injected linker error";}
 public static void glDeleteProgram(int id){programs.remove(id);}
 public static void glDeleteShader(int id){shaders.remove(id);}
 public static void glUseProgram(int id){}
 public static int glGetUniformLocation(int id,String name){Hooks.uniformQueries++;return Hooks.missingUniform?-1:id+200;}
 public static void glUniform2f(int l,float x,float y){Hooks.lastUniform=l;}
 public static void glUniform1i(int l,int x){Hooks.lastUniform=l;}
 public static void glUniform1f(int l,float x){Hooks.lastUniform=l;}
 public static void glViewport(int x,int y,int w,int h){}
 public static void glClear(int mask){}
 public static void glDrawArrays(int mode,int first,int count){}
}
