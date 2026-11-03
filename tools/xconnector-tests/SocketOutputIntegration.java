package com.winlator.xconnector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class SocketOutputIntegration {
    static { System.loadLibrary("winlator"); }
    static native int[] socketPair();
    static native byte[] receive(int fd, int length);
    static native int fdCount();
    static native void close(int fd);
    static native void interruptNext();
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    /** A peer that does not read must not block the writer; once it reads, every byte arrives once and in order. */
    static void nonBlockingOverARealSocket() throws Exception {
        int[] p = socketPair();
        ClientSocket client = new ClientSocket(p[0]);
        XOutputStream out = new XOutputStream(client, 1 << 16);
        int[] told = {0};
        out.enableNonBlocking(client::sendNoWait, () -> told[0]++);
        final int total = 2 * 1024 * 1024;
        long start = System.nanoTime();
        for (int i = 0; i < total / 4; i++) {
            try (XStreamLock ignored = out.lock()) { out.writeInt(i); }
        }
        check((System.nanoTime() - start) / 1_000_000 < 5000, "writer never blocks on an unread socket");
        check(out.hasPending() && told[0] > 0, "unread output is queued and the connector is told");
        byte[][] received = new byte[1][];
        Thread reader = new Thread(() -> received[0] = receive(p[1], total));
        reader.setUncaughtExceptionHandler((t, e) -> { e.printStackTrace(); System.exit(2); });
        reader.start();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (out.hasPending() && System.nanoTime() < deadline) {
            try { out.drainPending(); } catch (IOException e) { throw new AssertionError("drain failed: " + e); }
            Thread.sleep(1);
        }
        check(!out.hasPending(), "the queue drains once the peer reads");
        reader.join(10000);
        check(!reader.isAlive() && received[0] != null, "reader received everything");
        java.nio.ByteBuffer view = java.nio.ByteBuffer.wrap(received[0]).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // This test stream keeps the ByteBuffer default (big endian); the connector sets little endian in production.
        for (int i = 0; i < total / 4; i++) {
            int expected = i;
            int actual = view.order(java.nio.ByteOrder.BIG_ENDIAN).getInt(i * 4);
            check(actual == expected, "byte order and content preserved across the queue at " + i);
        }
        close(p[0]); close(p[1]);
    }

    public static void main(String[] args) throws Exception {
        boolean baseline = args.length > 0;
        int[] pair = socketPair();
        ClientSocket socket = new ClientSocket(pair[0]);
        ByteBuffer data = ByteBuffer.allocateDirect(40);
        for (int i=0;i<40;i++) data.put((byte)i);
        data.flip();
        socket.write(data);
        XOutputStream growing = new XOutputStream(1);
        int allocations = 0;
        long copiedPrefix = 0;
        for (int i=0;i<10000;i++) {
            ByteBuffer before = growing.buffer;
            int used = before.position();
            growing.writeInt(i);
            if (before != growing.buffer) { allocations++; copiedPrefix += used; }
        }
        growing.buffer.flip();
        for (int i=0;i<10000;i++) check(growing.buffer.getInt()==i,"growth preserves data");
        System.out.println("OUTPUT GROWTH: allocations="+allocations+", copied valid-prefix bytes="+copiedPrefix);
        if (!baseline) check(allocations<=12,"bounded geometric growth");
        if (baseline) {
            check(data.position()==3, "baseline short-write reproduction");
            check(receive(pair[1],3).length==3, "baseline sent only 3 of 40 bytes");
            System.out.println("BASELINE: 37 of 40 frame-event bytes lost after short write");
            close(pair[0]);close(pair[1]);return;
        }
        check(data.position()==40, "all bytes consumed");
        byte[] received=receive(pair[1],40);
        for(int i=0;i<40;i++) check(received[i]==(byte)i,"exact short-write byte ordering");
        data.position(7);data.limit(21);socket.write(data);
        check(Arrays.equals(receive(pair[1],14), Arrays.copyOfRange(received,7,21)), "nonzero offset preserved");
        interruptNext();data.clear().limit(5);socket.write(data);
        check(Arrays.equals(receive(pair[1],5), Arrays.copyOfRange(received,0,5)), "EINTR retried without missing bytes");
        data.clear().limit(33);interruptNext();socket.sendAncillaryMsg(data,pair[0]);
        check(Arrays.equals(receive(pair[1],33),Arrays.copyOfRange(received,0,33)), "ancillary short-write bytes");
        check(fdCount()==1,"one ancillary FD despite short writes and EINTR");
        // Packet stream of real XOutputStream locks/flushes: Complete (40 bytes), Idle (32).
        Thread reader=new Thread(()->{
            byte[] packets=receive(pair[1],7200);
            for(int i=0;i<7200;i++) check(packets[i]==(byte)(i%72),"Complete/Idle byte stream");
        });
        reader.setUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(2);});reader.start();
        XOutputStream out=new XOutputStream(socket,512);
        for(int frame=0;frame<100;frame++) {
            try(XStreamLock ignored=out.lock()){for(int i=0;i<40;i++)out.writeByte((byte)i);}
            try(XStreamLock ignored=out.lock()){for(int i=40;i<72;i++)out.writeByte((byte)i);}
        }
        reader.join(5000);check(!reader.isAlive(),"receiver finishes 100 frame pairs");
        close(pair[1]);
        try { try(XStreamLock ignored=out.lock()){out.writeInt(1);} throw new AssertionError("missing broken-peer error"); }
        catch(IOException expected){}
        try { try(XStreamLock ignored=out.lock()){out.writeInt(2);} throw new AssertionError("missing terminal error"); }
        catch(IOException expected){check(out.buffer.position()==0,"failed stream clears unsent output without replay");}
        close(pair[0]);
        nonBlockingOverARealSocket();
        System.out.println("PASS real JNI/Unix socket: short writes, offsets, EINTR, FD once, 100 Complete/Idle pairs, broken-peer SIGPIPE protection, no replay and non-blocking output");
    }
}
