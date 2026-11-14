package com.winlator.xserver.harness;

import com.winlator.xserver.Drawable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * JVM tests: Drawable's static block loads libwinlator, which does not exist on the host. Under Robolectric this shadow
 * replaces that block, so a real XServer (windows, input, grabs, selections) can be built; drawing calls stay unused.
 */
@Implements(Drawable.class)
public class ShadowNoNativeDrawable {
    @Implementation
    protected static void __staticInitializer__() { }
}
