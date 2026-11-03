package test;
import java.util.*;
public class Hooks {
 public static int id,params,framebuffers,boundFramebuffer,attribQueries,enabledAttribute,attributeEnables,uniformQueries,lastUniform,failShaderType,blits,firstPixel;
 public static boolean failLink,missingAttribute,missingUniform;
 public static int framebufferDeletes;
 public static Map<Long,Boolean> visible=new HashMap<>();
 public static Map<Long,int[]> geometry=new HashMap<>();
 public static void windowUpdate(long id,boolean shown,int z,int a,int b,int c,int d,int e,int f,int g,int h){visible.put(id,shown);geometry.put(id,new int[]{a,b,c,d,e,f,g,h});}
}
