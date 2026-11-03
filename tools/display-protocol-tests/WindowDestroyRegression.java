package com.winlator.xserver;
import com.winlator.xserver.events.*;
import java.util.*;
import com.winlator.xserver.WindowManager.OnWindowModificationListener;
public class WindowDestroyRegression extends XResourceManager {
 final Window rootWindow=new Window(1);
 final Map<Integer,Window> windows=new LinkedHashMap<>();
 final DrawableManager drawableManager=new DrawableManager();
 final ListenerSnapshot<WindowManager.OnWindowModificationListener> onWindowModificationListeners=new ListenerSnapshot<>(WindowManager.OnWindowModificationListener[]::new);
 Window focusedWindow;
 final List<Integer> freed=new ArrayList<>(),destroyed=new ArrayList<>();
 WindowDestroyRegression(){windows.put(1,rootWindow);onWindowModificationListeners.add(new WindowManager.OnWindowModificationListener(){public void onDestroyWindow(Window w){if(w.parent!=null)throw new AssertionError("renderer sees destroyed window still linked");destroyed.add(w.id);}});}
 Window getWindow(int id){return windows.get(id);}
 void revertFocus(){focusedWindow=null;}
 public void triggerOnFreeResourceListener(XResource r){freed.add(r.id);}
 Window add(Window parent,int id){Window w=new Window(id);w.parent=parent;parent.children.add(w);windows.put(id,w);return w;}
 /* PRODUCTION */
 public static void main(String[]args){
  int mode=Integer.parseInt(args[0]);WindowDestroyRegression m=new WindowDestroyRegression();Window parent=m.add(m.rootWindow,2);Window child=m.add(parent,3);Window grandchild=m.add(child,4);
  if(mode==1){m.destroyWindow(1);if(m.windows.size()!=4||!m.destroyed.isEmpty())throw new AssertionError("root destroyed");}
  else if(mode==2){m.destroyWindow(99);if(m.windows.size()!=4||!m.destroyed.isEmpty())throw new AssertionError("unknown window callbacks");}
  else {if(mode==3)parent.attributes.mapped=false;m.destroyWindow(2);if(m.windows.size()!=1||m.rootWindow.children.size()!=0)throw new AssertionError("subtree survives destruction");if(!m.destroyed.equals(List.of(4,3,2)))throw new AssertionError("missing child-first destruction callbacks "+m.destroyed);if(mode==4){m.destroyWindow(2);if(m.destroyed.size()!=3)throw new AssertionError("duplicate callbacks");}}
  System.out.println("PASS window-destroy "+mode);
 }
}
