#!/usr/bin/env python3
"""Compile production QueryPointer + coordinate/mask methods; count temporary objects, verify reply bytes.
Optional root selects saved pre-fix sources. Android/X11 boundaries are small deterministic fixtures.
"""
from pathlib import Path
import os, subprocess, tempfile, sys
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[2]
base=root/'app/src/main/java/com/winlator/xserver'
def method(path, signature):
    text=path.read_text(); start=text.find(signature)
    if start<0:return ''
    begin=text.index('{',start); depth=1; i=begin+1
    while depth:
        depth+=(text[i]=='{')-(text[i]=='}');i+=1
    return text[start:i]
wm='\n'.join(method(base/'Window.java',s) for s in ['public short[] rootPointToLocal(', 'public int rootPointToLocalPacked('])
# Instrument the existing array allocation; the statement's semantics are unchanged.
wm=wm.replace('return new short[]{', 'arrays++; return new short[]{')
im='\n'.join(method(base/'InputDeviceManager.java',s) for s in ['public Bitmask getKeyButMask()', 'public long getKeyButMaskBits()'])
query=method(base/'requests/WindowRequests.java','public static void queryPointer(')
source=r"""
import java.io.*;import java.nio.*;import java.util.*;
public class QueryPointerAllocationRegression {
static class Bitmask {static int allocations;long bits;Bitmask(){allocations++;}Bitmask(long v){this();bits=v;}long getBits(){return bits;}void join(Bitmask b){bits|=b.bits;}}
static class Window {static int arrays;int id;short x,y;Window parent;Window child;Window(int id,int x,int y,Window parent){this.id=id;this.x=(short)x;this.y=(short)y;this.parent=parent;}
Window getChildByCoords(short x,short y){return child;}
/* WINDOWS */
}
static class InputDeviceManager {XServer xServer;InputDeviceManager(XServer s){xServer=s;}
/* INPUT */
}
static class XServer {WindowManager windowManager=new WindowManager();Pointer pointer=new Pointer();Keyboard keyboard=new Keyboard();InputDeviceManager inputDeviceManager=new InputDeviceManager(this);boolean relative;boolean isRelativeMouseMovement(){return relative;}}
static class WindowManager {Window rootWindow;Window target;Window getWindow(int id){return target!=null&&target.id==id?target:null;}}
static class Pointer {short x,y;Bitmask buttons=new Bitmask();short getClampedX(){return x;}short getClampedY(){return y;}Bitmask getButtonMask(){return buttons;}}
static class Keyboard {Bitmask mods=new Bitmask();Bitmask getModifiersMask(){return mods;}}
static class XClient {XServer xServer=new XServer();short getSequenceNumber(){return 7;}}
static class XInputStream {int id;int readInt(){return id;}}
static class XRequestError extends Exception{}static class BadWindow extends XRequestError{BadWindow(int i){}}
interface XStreamLock extends AutoCloseable {void close();}
static class XOutputStream {ByteBuffer b=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);XStreamLock lock(){return ()->{};}void writeByte(byte v){b.put(v);}void writeShort(short v){b.putShort(v);}void writeInt(int v){b.putInt(v);}void writePad(int n){for(int i=0;i<n;i++)b.put((byte)0);}}
static final byte RESPONSE_CODE_SUCCESS=1;
/* QUERY */
public static void main(String[] args)throws Exception {
 Random random=new Random(291);int total=0;
 for(int i=0;i<20000;i++){
  XClient c=new XClient();XServer s=c.xServer;
  Window root=new Window(1,random.nextInt(),random.nextInt(),null);
  Window mid=new Window(2,random.nextInt(),random.nextInt(),root);
  Window leaf=new Window(3,random.nextInt(),random.nextInt(),mid);leaf.child=new Window(9,0,0,leaf);
  s.windowManager.rootWindow=root;s.windowManager.target=leaf;
  s.pointer.x=(short)random.nextInt();s.pointer.y=(short)random.nextInt();s.relative=(i%2==0);
  s.pointer.buttons.bits=random.nextLong();s.keyboard.mods.bits=random.nextLong();
  XInputStream in=new XInputStream();in.id=3;XOutputStream out=new XOutputStream();
  short lx=s.pointer.x,ly=s.pointer.y;for(Window w=leaf;w!=null;w=w.parent){lx-=w.x;ly-=w.y;}
  ByteBuffer expected=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
  expected.put((byte)1).put((byte)(s.relative?0:1)).putShort((short)7).putInt(0).putInt(1).putInt(9)
   .putShort(s.pointer.x).putShort(s.pointer.y).putShort(lx).putShort(ly).putShort((short)(s.pointer.buttons.bits|s.keyboard.mods.bits));
  Window.arrays=0;Bitmask.allocations=0;queryPointer(c,in,out);total+=Window.arrays+Bitmask.allocations;
  if(!Arrays.equals(expected.array(),out.b.array()))throw new AssertionError("Protocol mismatch "+i);
 }
 System.out.println("20000 exact 32-byte replies passed; temporary arrays/Bitmask objects="+total+" ("+total/20000.0+"/request)");
 if(total!=0)throw new AssertionError("QueryPointer still creates temporary serialization objects");
 XClient c=new XClient();try{queryPointer(c,new XInputStream(),new XOutputStream());throw new AssertionError("bad window accepted");}catch(BadWindow expected){}
}
}
""".replace('/* WINDOWS */',wm).replace('/* INPUT */',im).replace('/* QUERY */',query)
with tempfile.TemporaryDirectory(prefix='wow-query-pointer-') as td:
    td=Path(td);src=td/'QueryPointerAllocationRegression.java';src.write_text(source)
    java=Path(os.environ['JAVA_HOME'])/'bin'
    subprocess.run([str(java/'javac'),str(src)],check=True)
    subprocess.run([str(java/'java'),'-cp',str(td),'QueryPointerAllocationRegression'],check=True)
