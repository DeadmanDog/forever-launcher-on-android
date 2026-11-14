package com.winlator.xserver.errors;

/**
 * SYNC 3.1 Fence error. SYNC has three errors (Counter, Alarm, Fence), so Fence is the extension's first error + 2. The fence
 * table throws it without knowing the extension; SyncExtension re-throws it with the code derived from its first error.
 */
public class BadFence extends XRequestError {
    /** First error 129 (after MIT-SHM's 128) + 2, the code of the default registration order. */
    public static final int DEFAULT_CODE = 131;

    public BadFence(int id) {
        super(DEFAULT_CODE, id);
    }

    public BadFence(byte syncFirstErrorId, int id) {
        super((syncFirstErrorId & 0xFF) + 2, id);
    }
}
