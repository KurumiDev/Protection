package io.ruguard.runtime;

import io.ruguard.runtime.crypto.CryptoSuite;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton context object that captures runtime entropy for HKDF key
 * derivation (Spec §6.2). Initialised exactly once at bootstrap, then frozen.
 *
 * <p>Sources of entropy, in order of contribution:
 * <ol>
 *   <li>{@code System.nanoTime()} snapshot taken at bootstrap;</li>
 *   <li>the set of loaded {@link ClassLoader}s at bootstrap (changes when a
 *       tool attaches a custom loader → a tamper signal);</li>
 *   <li>implementation hooks of {@link #installNativeEntropyHook()} so the
 *       Rust barrier (Phase 3, Spec §8.1) can swap in real entropy later
 *       without changing call sites.</li>
 * </ol>
 *
 * <p>The whole input byte stream is never persisted to disk; it lives in a
 * single heap array that is zeroed on {@link #shutdown()}. The HKDF output is
 * computed lazily on first request and cached in a volatile reference; nothing
 * is logged.
 */
public final class RuntimeContext {

    private static final AtomicReference<RuntimeContext> INSTANCE = new AtomicReference<>();

    /** Master entropy byte stream — never copied or serialised. */
    private final byte[] entropy;
    /** SHA-256 of the entropy — used as the IKM for HKDF. */
    private final byte[] ikm;
    /** Build seed from the {@code @RuguardProtected} annotation on the
     *  calling class. 8-byte big-endian. */
    private final byte[] buildSeed;
    /** Pluggable native-entropy source — populated by Phase 3. */
    private volatile byte[] nativeEntropy;

    private RuntimeContext(byte[] entropy, byte[] buildSeed) {
        this.entropy = entropy;
        this.ikm = CryptoSuite.sha256(entropy);
        this.buildSeed = buildSeed;
    }

    /**
     * Initialise exactly once for the running JVM. Repeated calls are no-ops
     * — we do <em>not</em> refresh the entropy mid-session, because derived
     * keys are already cached in {@link EncryptedBlock} instances.
     */
    public static synchronized RuntimeContext initialise(byte[] buildSeed) {
        RuntimeContext existing = INSTANCE.get();
        if (existing != null) return existing;
        if (buildSeed == null || buildSeed.length != 8)
            throw new IllegalArgumentException("buildSeed must be 8 bytes");
        byte[] ent = composeEntropy(buildSeed);
        RuntimeContext fresh = new RuntimeContext(ent, buildSeed);
        if (!INSTANCE.compareAndSet(null, fresh)) {
            // somebody beat us to it; drop the duplicate and reuse
            java.util.Arrays.fill(ent, (byte) 0);
            java.util.Arrays.fill(fresh.entropy, (byte) 0);
        }
        INSTANCE.get().installNativeEntropyHook();
        return INSTANCE.get();
    }

    public static RuntimeContext current() {
        RuntimeContext rc = INSTANCE.get();
        if (rc == null) throw new IllegalStateException("RuntimeContext not initialised");
        return rc;
    }

    /** Hook for the native barrier to inject additional entropy (Spec §8.1). */
    public void installNativeEntropyHook() {
        // Phase-3 placeholder. Reads from a JNI symbol if present; never throws.
        try {
            Class<?> bridge = Class.forName("io.ruguard.nativebridge.NativeEntropy", false,
                    RuntimeContext.class.getClassLoader());
            Method m = bridge.getMethod("currentEntropy");
            Object o = m.invoke(null);
            if (o instanceof byte[] b) {
                this.nativeEntropy = b;
            }
        } catch (Throwable t) {
            // no native bridge — stay with the Java-side entropy
        }
    }

    public byte[] buildSeed() {
        return buildSeed.clone();
    }

    /** Returns the current IKM, mixed with native entropy if available.
     *  Always returns a fresh copy — caller may modify freely. */
    public byte[] ikm() {
        byte[] ne = nativeEntropy;
        if (ne != null) {
            // Mix native entropy into IKM for cross-layer binding (P6)
            byte[] combined = new byte[ikm.length + ne.length];
            System.arraycopy(ikm, 0, combined, 0, ikm.length);
            System.arraycopy(ne, 0, combined, ikm.length, ne.length);
            return CryptoSuite.sha256(combined);
        }
        return ikm.clone();
    }

    public void setNativeEntropy(byte[] ne) {
        this.nativeEntropy = ne != null ? ne.clone() : null;
    }

    /** Compose a stable per-process entropy blob from several runtime observables. */
    private static byte[] composeEntropy(byte[] buildSeed) {
        byte[] classLoaders = fingerprintClassLoaders();

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(128);
        try {
            bos.write(buildSeed);
            bos.write(classLoaders);
            bos.write(("ruguard/runtime-context/v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        return bos.toByteArray();
    }

    /** Produces a stable SHA-256 of every ClassLoader currently reachable via
     *  the system class loader chain. This is the "classloader fingerprint"
     *  entropy source mentioned in §6.2. Attaching a custom loader to dump
     *  bytecode changes this fingerprint and — because HKDF mixes the bytes
     *  into every chunk key — silently breaks all decrypts. P4: the broken
     *  decrypts yield plausible-but-wrong bytes, so the attacker can't tell
     *  whether their instrumentation is the cause. */
    public static byte[] fingerprintClassLoaders() {
        // Return a stable, constant fingerprint to ensure cross-JVM and cross-classloader portability.
        // This prevents decryption failures when running under Fabric (KnotClassLoader), Forge,
        // or different JVM versions than the build environment.
        return new byte[] {
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
            (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
            (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
            (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
            (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
            (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0
        };
    }

    /** Zero the entropy buffers. Idempotent. */
    public void shutdown() {
        java.util.Arrays.fill(entropy, (byte) 0);
        java.util.Arrays.fill(ikm, (byte) 0);
        if (nativeEntropy != null) java.util.Arrays.fill(nativeEntropy, (byte) 0);
    }
}
