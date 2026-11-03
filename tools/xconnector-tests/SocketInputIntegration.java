package com.winlator.xconnector;
import java.nio.ByteBuffer;
import java.io.IOException;
public final class SocketInputIntegration {
 static {System.loadLibrary("winlator");}
 static native int[] socketPair(); static native void close(int fd); static native void send(int fd,int count);
 static native void interruptReads(int count); static native void failRead(int errno); static native int calls(boolean ancillary);
 static native boolean isCloseOnExec(int fd); static native int fdCount();
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static int receive(ClientSocket socket,ByteBuffer buffer,boolean ancillary)throws IOException{return ancillary?socket.recvAncillaryMsg(buffer):socket.read(buffer);}
 public static void main(String[]args)throws Exception {
  boolean baseline=args.length>0&&args[0].equals("baseline");boolean ancillary=args.length>1&&args[1].equals("ancillary");
  int[]pair=socketPair();ClientSocket socket=new ClientSocket(pair[0]);ByteBuffer buffer=ByteBuffer.allocateDirect(16);buffer.position(3);buffer.limit(7);send(pair[1],ancillary?2:0);interruptReads(3);
  try{int n=receive(socket,buffer,ancillary);check(n==4&&buffer.position()==7,"interrupted read payload/cursor");}
  catch(IOException e){if(baseline){System.out.println("REPRODUCED: interrupted "+(ancillary?"recvmsg":"read")+" disconnects live client: "+e);close(pair[0]);close(pair[1]);System.exit(2);}throw e;}
  check(calls(ancillary)==4,"retry EINTR exactly three times");
  if(ancillary){for(int i=0;i<2;i++){int fd=socket.getAncillaryFd();check(fd>=0&&isCloseOnExec(fd),"FD received once and CLOEXEC");close(fd);}check(!socket.hasAncillaryFds(),"no duplicate FD");}
  for(int i=0;i<4;i++)check(buffer.get(3+i)==i+1,"ordered bytes");close(pair[0]);close(pair[1]);
  if(baseline)throw new AssertionError("baseline unexpectedly passed");
  for(boolean withFds:new boolean[]{false,true}) {
   pair=socketPair();socket=new ClientSocket(pair[0]);buffer.clear();send(pair[1],withFds?1:0);interruptReads(0);check(receive(socket,buffer,withFds)==4,"normal one-syscall read");check(calls(withFds)==1,"healthy receive uses one syscall");if(withFds)close(socket.getAncillaryFd());
   close(pair[1]);buffer.clear();check(receive(socket,buffer,withFds)==-1,"true EOF preserved");close(pair[0]);
   pair=socketPair();socket=new ClientSocket(pair[0]);buffer.clear();interruptReads(0);failRead(5);try{receive(socket,buffer,withFds);throw new AssertionError("EIO swallowed");}catch(IOException expected){}check(calls(withFds)==1,"no non-EINTR busy retry");close(pair[0]);close(pair[1]);
  }
  pair=socketPair();socket=new ClientSocket(pair[0]);int before=fdCount();send(pair[1],48);buffer.clear();try{socket.recvAncillaryMsg(buffer);throw new AssertionError("truncated FD message accepted");}catch(IOException expected){}check(!socket.hasAncillaryFds(),"truncated rights not published");check(fdCount()==before,"truncated received FDs closed");close(pair[0]);close(pair[1]);
  pair=socketPair();socket=new ClientSocket(pair[0]);before=fdCount();send(pair[1],3);buffer.clear();socket.recvAncillaryMsg(buffer);check(fdCount()==before+3,"received descriptor ownership");socket.closeAncillaryFds();socket.closeAncillaryFds();check(fdCount()==before,"disconnect drains only unconsumed FDs once");close(pair[0]);close(pair[1]);
  try{XConnectorEpollNative.receiveSocket(-1,ByteBuffer.allocate(4),0,4,null);throw new AssertionError("heap buffer accepted");}catch(IOException expected){}
  try{XConnectorEpollNative.receiveSocket(-1,ByteBuffer.allocateDirect(4),3,2,null);throw new AssertionError("invalid range accepted");}catch(IOException expected){}
  for(int i=0;i<1000;i++){pair=socketPair();socket=new ClientSocket(pair[0]);send(pair[1],1);buffer.clear();interruptReads(i%3);check(socket.recvAncillaryMsg(buffer)==4,"repeated frame import");int fd=socket.getAncillaryFd();check(fd>=0,"frame FD");close(fd);close(pair[0]);close(pair[1]);}
  System.out.println("PASS input EINTR, payload offsets, EOF/error, FD once/CLOEXEC/truncation/cleanup, invalid buffers and 1000 frame imports");
 }
}
