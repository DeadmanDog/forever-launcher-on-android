package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;

public class SHMSegmentManager {
    private final SysVSharedMemory sysVSharedMemory;
    private final SparseArray<ByteBuffer> shmSegments = new SparseArray<>();
    private final SparseArray<Object> owners = new SparseArray<>();

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    public void attach(int xid, int shmid) {
        attach(xid, shmid, null);
    }

    /** [owner] is the client that attached the segment; its attachments are released when it disconnects. */
    public void attach(int xid, int shmid, Object owner) {
        if (shmSegments.indexOfKey(xid) >= 0) detach(xid);
        ByteBuffer data = sysVSharedMemory.attach(shmid);
        if (data != null) {
            shmSegments.put(xid, data);
            if (owner != null) owners.put(xid, owner);
        }
    }

    public void detach(int xid) {
        ByteBuffer data = shmSegments.get(xid);
        if (data != null) {
            sysVSharedMemory.detach(data);
            shmSegments.remove(xid);
        }
        owners.remove(xid);
    }

    /** Detaches every segment the disconnected [owner] attached and never detached itself. */
    public void detachOwned(Object owner) {
        for (int i = owners.size() - 1; i >= 0; i--) {
            if (owners.valueAt(i) == owner) detach(owners.keyAt(i));
        }
    }

    /** Called only after the X socket dispatcher has stopped using these buffers. */
    public void detachAll() {
        for (int i = shmSegments.size() - 1; i >= 0; i--) detach(shmSegments.keyAt(i));
    }

    public ByteBuffer getData(int xid) {
        return shmSegments.get(xid);
    }
}
