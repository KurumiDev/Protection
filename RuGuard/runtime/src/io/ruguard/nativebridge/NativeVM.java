package io.ruguard.nativebridge;

/**
 * JNI bridge for custom Virtual Machine execution (Spec §7.3).
 * Loads the native library and exposes the executeNative JNI method.
 */
public final class NativeVM {

    static {
        LibraryLoader.load();
    }

    private NativeVM() {}

    /**
     * Native execution loop.
     * Takes bytecode, constant pool, frame configurations, variables and executing context class.
     */
    public static native Object executeNative(
            byte[] bytecode,
            Object[] constantPool,
            int maxStack,
            int maxLocals,
            Object[] args,
            Class<?> hostClass
    );
}
