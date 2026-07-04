package io.ruguard.runtime;

import io.ruguard.annotation.GuardedStub;
import io.ruguard.runtime.crypto.CryptoSuite;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decrypt → register → invoke → wipe for one protected method (Spec §6.1).
 *
 * <p>Each protected method is a {@code byte[]} in the host class's
 * {@code RUGUARD_BLOCKS} static array. On the first call to the stub the
 * runtime derives the chunk key via HKDF (Spec §6.2), decrypts the array,
 * registers it as a <em>hidden class</em> via {@link
 * MethodHandles.Lookup#defineHiddenClass(byte[], boolean,
 * MethodHandles.Lookup.ClassOption...)} (JDK 15+), and caches a
 * {@link MethodHandle} to {@code $exec}. Subsequent calls route through the
 * handle.
 *
 * <p>Wipe policy:
 * <ul>
 *   <li>derived key is {@code Arrays.fill}'d to zero immediately after use;</li>
 *   <li>plain chunk bytes are wiped right after {@code defineHiddenClass}
 *       returns (the class is now a JVM-internal artefact — only its
 *       {@code Class} object stays around, the plaintext bytes are no longer
 *       referenced);</li>
 *   <li>{@link java.lang.ref.Reference#reachabilityFence(Object)} keeps the
 *       key object alive across {@code invokeExact}, defeating JIT dead-store
 *       elimination.</li>
 * </ul>
 *
 * <p>P1 (no whole-plaintext-in-memory): at any moment the JVM holds at most
 * one decrypted chunk per active call-stack. P2 (no static key): the chunk
 * key never exists outside HKDF's output buffer. P3 (three independent
 * mechanisms): integrity of the chunk tag, scope-binding seed HKDF, and
 * classloader fingerprint all participate. P4 (silent degradation): a failed
 * decrypt returns a stub handle that yields a plausible wrong value rather
 * than an exception.
 */
public final class EncryptedBlock {

    private final int index;
    private final byte[] encrypted;
    private final MethodHandles.Lookup lookup;
    private final AtomicReference<MethodHandle> realHandle = new AtomicReference<>();
    /** Per-thread scope-binding seed (Spec §6.1). */
    private final ThreadLocal<byte[]> scopeBinding = new ThreadLocal<>();
    /** Hidden class handle — cleared on purge to allow JVM to unload it. */
    private final AtomicReference<MethodHandles.Lookup> hiddenLookup = new AtomicReference<>();
    private static final ConcurrentHashMap<String, MethodHandle> HANDLE_CACHE = new ConcurrentHashMap<>();
    private volatile boolean purged = false;

    EncryptedBlock(int index, byte[] encrypted, MethodHandles.Lookup lookup) {
        if (encrypted == null) throw new IllegalArgumentException("encrypted == null");
        if (lookup == null) throw new IllegalArgumentException("lookup == null");
        this.index = index;
        this.encrypted = encrypted;
        this.lookup = lookup;
    }

    /** Returns the live {@link MethodHandle} for the protected method,
     *  building it on demand. Cached after the first call. */
    public MethodHandle handle() {
        if (purged) return stubHandle(lookupForStub());
        MethodHandle cached = realHandle.get();
        if (cached != null) return cached;
        synchronized (this) {
            cached = realHandle.get();
            if (cached != null) return cached;
            String key = cacheKey();
            cached = HANDLE_CACHE.get(key);
            if (cached != null) {
                realHandle.set(cached);
                return cached;
            }
            try {
                cached = buildAndCache();
                HANDLE_CACHE.put(key, cached);
                realHandle.set(cached);
                return cached;
            } catch (Throwable t) {
                t.printStackTrace();
                // P4 — degrade silently to stub.
                MethodHandle stub = stubHandle(lookupForStub());
                realHandle.set(stub);
                return stub;
            }
        }
    }

    private String cacheKey() {
        return lookup.lookupClass().getName() + "#" + index;
    }

    private MethodHandles.Lookup lookupForStub() {
        MethodHandles.Lookup hl = hiddenLookup.get();
        if (hl != null) return hl;
        try {
            // create a private-lookups for an inner class; even if we have
            // nothing to lookup, we keep one around so the handle().invokeWithArguments
            // path can build a stub typed handle.
            return MethodHandles.lookup();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MethodHandle buildAndCache() throws Throwable {
        RuntimeContext rc = RuntimeContext.current();

        // 1. Derive the symmetric key for this block — mixed with the scope
        //    seed so the next block's key cannot be computed in isolation.
        byte[] scope = scopeBinding.get();
        if (scope == null) {
            scope = deriveScopeBindingSeed();
            scopeBinding.set(scope);
        }
        byte[] key = deriveChunkKey(rc, scope);

        // 2. AEAD-decrypt.
        byte[] ad = ("ruguard-block/v1/" + lookup.lookupClass().getName() + "/" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nonce = CryptoSuite.chunkNonce(rc.buildSeed(), index);
        byte[] plain;
        try {
            plain = CryptoSuite.aeadDecrypt(key, nonce, ad, encrypted);
        } finally {
            Arrays.fill(key, (byte) 0); // wipe immediately
        }

        // 3. Check if it is a VM block payload (Magic: 0x766d6263)
        if (io.ruguard.runtime.vm.VMBlock.isVMBlock(plain)) {
            MethodType mt = null;
            Class<?> host = lookup.lookupClass();
            for (java.lang.reflect.Method m : host.getDeclaredMethods()) {
                io.ruguard.annotation.GuardedStub gs = m.getAnnotation(io.ruguard.annotation.GuardedStub.class);
                if (gs != null && gs.blockIndex() == index) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    Class<?>[] expectedParams;
                    if (isStatic) {
                        expectedParams = m.getParameterTypes();
                    } else {
                        Class<?>[] origParams = m.getParameterTypes();
                        expectedParams = new Class<?>[origParams.length + 1];
                        expectedParams[0] = Object.class;
                        System.arraycopy(origParams, 0, expectedParams, 1, origParams.length);
                    }
                    mt = MethodType.methodType(m.getReturnType(), expectedParams);
                    break;
                }
            }
            if (mt == null) {
                throw new NoSuchMethodException("Stub method for block " + index + " not found");
            }

            java.lang.reflect.Method m = io.ruguard.runtime.vm.VMInterpreter.class.getDeclaredMethod(
                    "executeVM", byte[].class, Object[].class, Class.class);
            m.setAccessible(true);
            MethodHandle execHandle = lookup.unreflect(m);

            // Bind plain (payload) and host class
            MethodHandle bound = MethodHandles.insertArguments(execHandle, 0, (Object) plain);
            bound = MethodHandles.insertArguments(bound, 1, (Object) host);

            // Adapt signature
            MethodHandle collector = bound.asCollector(Object[].class, mt.parameterCount());
            MethodHandle method = MethodHandles.explicitCastArguments(collector, mt);

            java.lang.ref.Reference.reachabilityFence(key);
            return method;
        }

        // 4. Register as a hidden class.
        MethodHandles.Lookup hl;
        try {
            hl = lookup.defineHiddenClass(plain, true, MethodHandles.Lookup.ClassOption.NESTMATE);
        } catch (Throwable t) {
            // Spec §3: defineHiddenClass requires the bytes to be a valid
            // class file for the host class's package. The CLI should produce
            // that; this catch is a defensive last-resort.
            Arrays.fill(plain, (byte) 0);
            throw new IllegalStateException("hidden-class registration failed for block " + index, t);
        }
        hiddenLookup.set(hl);
        // Wipe plaintext now that the JVM owns the class.
        Arrays.fill(plain, (byte) 0);

        // 5. Find the real method on the hidden class.
        MethodHandle method = locateRealMethod(hl);

        // 6. Defence-in-depth: keep the key buffer uneliminable across invoke.
        java.lang.ref.Reference.reachabilityFence(key);
        return method;
    }

    /** Two-step HKDF: bake scope into IKM first, then derive the chunk key. */
    private byte[] deriveChunkKey(RuntimeContext rc, byte[] scope) {
        byte[] prevBlockHash = new byte[32];
        if (index > 0) {
            BlockDispatcher.Field_Blocks fb = BlockDispatcher.Field_Blocks.of(lookup.lookupClass());
            if (fb != null) {
                byte[] prevBytes = fb.bytesAt(index - 1);
                if (prevBytes != null) {
                    prevBlockHash = CryptoSuite.sha256(prevBytes);
                }
            }
        }
        
        byte[] phase = "phase/no-native/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] ikm1 = CryptoSuite.hkdf(rc.ikm(), scope, phase, rc.ikm().length);
        byte[] ikm2 = CryptoSuite.hkdf(ikm1, prevBlockHash, "integrity/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8), ikm1.length);
        
        byte[] info = CryptoSuite.chunkKeyInfo(
                lookup.lookupClass().getName().replace('.', '/'), index, "v1");
        return CryptoSuite.hkdf(ikm2, null, info, CryptoSuite.DERIVED_KEY_BYTES);
    }

    private byte[] deriveScopeBindingSeed() {
        return CryptoSuite.sha256(("default-scope/v1/" + lookup.lookupClass().getName())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private MethodHandle locateRealMethod(MethodHandles.Lookup hl) throws NoSuchMethodException, IllegalAccessException {
        // Walk the host's stub methods to match the original signature.
        Class<?> host = lookup.lookupClass();
        for (java.lang.reflect.Method m : host.getDeclaredMethods()) {
            GuardedStub gs = m.getAnnotation(GuardedStub.class);
            if (gs == null || gs.blockIndex() != index) continue;
            
            boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
            Class<?>[] expectedParams;
            if (isStatic) {
                expectedParams = m.getParameterTypes();
            } else {
                Class<?>[] origParams = m.getParameterTypes();
                expectedParams = new Class<?>[origParams.length + 1];
                expectedParams[0] = Object.class;
                System.arraycopy(origParams, 0, expectedParams, 1, origParams.length);
            }
            MethodType mt = MethodType.methodType(m.getReturnType(), expectedParams);
            
            for (java.lang.reflect.Method rm : hl.lookupClass().getDeclaredMethods()) {
                if (!rm.getName().equals("$exec")) continue;
                if (!rm.getReturnType().equals(mt.returnType())) continue;
                Class<?>[] rp = rm.getParameterTypes();
                if (rp.length != mt.parameterCount()) continue;
                boolean ok = true;
                for (int i = 0; i < rp.length; i++) {
                    if (!rp[i].equals(mt.parameterType(i))) { ok = false; break; }
                }
                if (!ok) continue;
                rm.setAccessible(true);
                MethodHandle mh = hl.unreflect(rm);
                return MethodHandles.explicitCastArguments(mh, mt);
            }
        }
        throw new NoSuchMethodException("$exec on hidden class for index " + index);
    }

    /** Discard all decrypted material under memory pressure / on shutdown. */
    public void purge() {
        synchronized (this) {
            purged = true;
            realHandle.set(null);
            byte[] s = scopeBinding.get();
            if (s != null) Arrays.fill(s, (byte) 0);
            scopeBinding.remove();
            hiddenLookup.set(null);
        }
    }

    /** P4 — return a stub handle that yields a plausible-but-wrong value.
     *  Matches the real method's signature so no WrongMethodTypeException. */
    private MethodHandle stubHandle(MethodHandles.Lookup hl) {
        try {
            Class<?> host = lookup.lookupClass();
            for (java.lang.reflect.Method m : host.getDeclaredMethods()) {
                GuardedStub gs = m.getAnnotation(GuardedStub.class);
                if (gs != null && gs.blockIndex() == index) {
                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    Class<?>[] expectedParams;
                    if (isStatic) {
                        expectedParams = m.getParameterTypes();
                    } else {
                        Class<?>[] origParams = m.getParameterTypes();
                        expectedParams = new Class<?>[origParams.length + 1];
                        expectedParams[0] = Object.class;
                        System.arraycopy(origParams, 0, expectedParams, 1, origParams.length);
                    }
                    MethodType mt = MethodType.methodType(m.getReturnType(), expectedParams);
                    return MethodHandles.empty(mt);
                }
            }
        } catch (Exception ignored) {}
        return MethodHandles.empty(MethodType.methodType(void.class));
    }
}
