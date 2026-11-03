package test;
import java.nio.*;import java.util.*;import android.os.SharedMemory;
public class Hooks {
 public static Set<Long> buffers=java.util.concurrent.ConcurrentHashMap.newKeySet();public static Set<Integer> fds=java.util.concurrent.ConcurrentHashMap.newKeySet();public static Set<ByteBuffer> maps=Collections.newSetFromMap(new IdentityHashMap<>());
 public static long next=100;public static int fdNext=200,creates=0,uploads=0,glId=0,glDeletes=0,eglDestroyed=0,locks=0,unlocks=0;public static boolean ashmemFails=false,receiveFails=false,eglFails=false,lockFails=false;public static SharedMemory shared;public static boolean requireStopped=false,connectorStopped=false;
 public static int fd(){int n=++fdNext;fds.add(n);return n;}public static void close(int n){fds.remove(n);}
 public static long create(short w,short h){creates++;if(w<=0||h<=0)return 0;long n=++next;buffers.add(n);return n;}
 public static long receive(int fd){if(receiveFails)return 0;long n=++next;buffers.add(n);return n;}
 public static ByteBuffer lock(Object owner,long ptr){locks++;if(lockFails)return null;try{var f=owner.getClass().getDeclaredField("stride");f.setAccessible(true);f.set(owner,(short)32);}catch(Exception e){throw new RuntimeException(e);}return ByteBuffer.allocateDirect(32*16*4);}
 public static int unlock(long ptr){unlocks++;return -1;}
 public static void destroyLegacyGpu(long ptr){unlocks++;buffers.remove(ptr);}
 public static void destroy(long ptr){buffers.remove(ptr);}public static void destroy(long ptr,boolean cpuLocked){if(cpuLocked)unlocks++;buffers.remove(ptr);}public static ByteBuffer map(int fd,long size,int offset,boolean readonly){if(!fds.contains(fd))return null;var b=ByteBuffer.allocateDirect((int)size);maps.add(b);return b;}public static void unmap(ByteBuffer b){if(requireStopped&&!connectorStopped)throw new AssertionError("unmap before socket readers stopped");maps.remove(b);}
}
