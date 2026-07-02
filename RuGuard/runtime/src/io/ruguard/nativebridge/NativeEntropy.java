package io.ruguard.nativebridge;

/**
 * Native bridge for RuGuard (Spec §8).
 * Loads the native library and exposes the JNI methods.
 */
public final class NativeEntropy {

    static {
        LibraryLoader.load();
    }

    private NativeEntropy() {}

    /**
     * Native entropy source mixed into HKDF.
     * If debugging is detected, this method returns slightly altered entropy,
     * causing quiet failure of class decryption (P4).
     */
    public static native byte[] currentEntropy();
}
