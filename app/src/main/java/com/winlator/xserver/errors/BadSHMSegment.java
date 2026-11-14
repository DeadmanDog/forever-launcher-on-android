package com.winlator.xserver.errors;

/** MIT-SHM BadSeg: the first (and only) error of the extension, so its code is the extension's first error. */
public class BadSHMSegment extends XRequestError {
    public BadSHMSegment(byte firstErrorId, int id) {
        super(firstErrorId, id);
    }
}
