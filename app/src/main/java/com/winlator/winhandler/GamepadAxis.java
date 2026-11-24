package com.winlator.winhandler;

/** Stick values for the shared gamepad state (pure, host-tested: GamepadAxisTest). */
public final class GamepadAxis {
    private GamepadAxis() {}

    /**
     * 1.2.26: a stick value as the shared state's signed 16-bit axis, clamped to -1..1 first. Some controllers report slightly past
     * their range (the triggers were already clamped for 1.00-1.02); (short)(1.02 * 32767) wrapped to -32114: a stick pushed fully
     * one way read as fully the other way in the game for as long as it was held there.
     */
    public static short toShort(float value) {
        if (Float.isNaN(value)) return 0;
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }
}
