package com.winlator.sysvshm;

import android.os.SharedMemory;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import android.system.ErrnoException;
import android.util.SparseArray;

import com.winlator.xconnector.XConnectorEpoll;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class SysVSharedMemory {
    private final SparseArray<SHMemory> shmemories = new SparseArray<>();
    private int maxSHMemoryId = 0;

    static {
        System.loadLibrary("winlator");
    }

    private static class SHMemory {
        private int fd;
        private long size;
        private ByteBuffer data;
        private int attachments;
        private boolean deleted;

        // Constructor for SHMemory
        private SHMemory() {}

        // Synthetic access methods for accessing the private fields
        static int access$000(SHMemory shMemory) {
            return shMemory.fd;
        }

        static int access$002(SHMemory shMemory, int fd) {
            shMemory.fd = fd;
            return fd;
        }

        static long access$200(SHMemory shMemory) {
            return shMemory.size;
        }

        static long access$202(SHMemory shMemory, long size) {
            shMemory.size = size;
            return size;
        }

        static ByteBuffer access$300(SHMemory shMemory) {
            return shMemory.data;
        }

        static ByteBuffer access$302(SHMemory shMemory, ByteBuffer data) {
            shMemory.data = data;
            return data;
        }
    }

    public int getFd(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            return shmemory != null ? SHMemory.access$000(shmemory) : -1;
        }
    }

    public int get(long size) {
        synchronized (shmemories) {
            int index = shmemories.size();
            int fd = ashmemCreateRegion(index, size);
            if (fd < 0) fd = createSharedMemory("sysvshm-"+index, (int)size);
            if (fd < 0) return -1;

            SHMemory shmemory = new SHMemory();
            int id = ++maxSHMemoryId;
            SHMemory.access$002(shmemory, fd);
            SHMemory.access$202(shmemory, size);
            shmemories.put(id, shmemory);
            return id;
        }
    }

    public void delete(int shmid) {
        synchronized (shmemories) {
            SHMemory memory = shmemories.get(shmid);
            if (memory == null) return;
            memory.deleted = true;
            if (memory.fd != -1) {
                XConnectorEpoll.closeFd(memory.fd);
                memory.fd = -1;
            }
            // Closing the server FD must not invalidate another client's attachment.
            if (memory.attachments == 0) shmemories.remove(shmid);
        }
    }

    public void deleteAll() {
        synchronized (shmemories) {
            for (int i = shmemories.size() - 1; i >= 0; i--) delete(shmemories.keyAt(i));
        }
    }

    public ByteBuffer attach(int shmid) {
        synchronized (shmemories) {
            SHMemory memory = shmemories.get(shmid);
            if (memory == null || memory.deleted) return null;
            if (memory.data == null) memory.data = mapSHMSegment(memory.fd, memory.size, 0, true);
            if (memory.data != null) memory.attachments++;
            return memory.data;
        }
    }

    public void detach(ByteBuffer data) {
        if (data == null) return;
        synchronized (shmemories) {
            for (int i = 0; i < shmemories.size(); i++) {
                SHMemory memory = shmemories.valueAt(i);
                if (memory.data != data) continue;
                if (--memory.attachments == 0) {
                    unmapSHMSegment(memory.data, memory.size);
                    memory.data = null;
                    if (memory.deleted) shmemories.remove(shmemories.keyAt(i));
                }
                break;
            }
        }
    }

    private static int createSharedMemory(String name, int size) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                try (SharedMemory sharedMemory = SharedMemory.create(name, size)) {
                    Method getFdMethod = sharedMemory.getClass().getMethod("getFd");
                    Integer fd = (Integer) getFdMethod.invoke(sharedMemory);
                    // getFd is borrowed; return an independent descriptor to the caller.
                    return fd != null ? ParcelFileDescriptor.fromFd(fd).detachFd() : -1;
                }
            }
        } catch (ErrnoException | NoSuchMethodException | IllegalAccessException | InvocationTargetException | IOException e) {
        }
        return -1;
    }

    private static native int ashmemCreateRegion(int index, long size);

    public static native ByteBuffer mapSHMSegment(int fd, long size, int offset, boolean readonly);

    public static native void unmapSHMSegment(ByteBuffer data, long size);
}
