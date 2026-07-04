package io.ruguard.runtime;

import io.ruguard.annotation.GuardedStub;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hot-path dispatch table keyed by stub identity (class+index). The CLI
 * injects code into each guarded stub that calls
 * {@code BlockDispatcher.dispatch(host, index, args)}; this dispatcher routes
 * the call to the live implementation hidden behind {@link EncryptedBlock}.
 *
 * <p>The dispatcher caches one {@link MethodHandle} per (class, index) so
 * the decrypt-execute-wipe cost is paid exactly once per method per session.
 * Subsequent invocations resolve to the handle in O(1).
 *
 * <p>P4 (silent degradation): if the live handle errors, the dispatcher
 * returns a {@code defaultOf(returnType)} — {@code 0}, {@code false},
 * {@code null} — and never surfaces an exception.
 */
public final class BlockDispatcher {

    /** {@link EncryptedBlock} per "className#index" — populated on first call. */
    private static final ConcurrentHashMap<String, EncryptedBlock> SLOTS = new ConcurrentHashMap<>();

    private BlockDispatcher() {}

    /**
     * Called from generated stub bytecode. {@code host} is the loaded class
     * object, {@code idx} is the {@link GuardedStub#blockIndex()}, {@code args}
     * is the original arg array.
     */
    public static Object dispatch(Class<?> host, int idx, Object[] args) {
        AntiTamper.quickCheck();
        EncryptedBlock block = blockFor(host, idx);
        if (block == null) {
            System.err.println("[BlockDispatcher] Failed to resolve encrypted block for class " + host.getName() + " index " + idx);
            AntiTamper.fail(); // aborts with "Invalid payload"
            return defaultFor(void.class);
        }
        MethodHandle h = block.handle();
        try {
            switch (args.length) {
                case 0: return h.invoke();
                case 1: return h.invoke(args[0]);
                case 2: return h.invoke(args[0], args[1]);
                case 3: return h.invoke(args[0], args[1], args[2]);
                case 4: return h.invoke(args[0], args[1], args[2], args[3]);
                case 5: return h.invoke(args[0], args[1], args[2], args[3], args[4]);
                default:
                    return h.invokeWithArguments(args);
            }
        } catch (Throwable t) {
            System.err.println("[BlockDispatcher] EXCEPTION in dispatch for " + host.getName() + " index " + idx + ": " + t);
            t.printStackTrace();
            // P4 — silent degradation.
            return defaultFor(returnTypeOf(host, idx));
        }
    }

    /** Equivalent entry that immediately installs a freshly-built block
     *  (used by tests / first-invoke bootstrap). */
    public static void install(Class<?> host, int idx, EncryptedBlock block) {
        SLOTS.put(key(host, idx), block);
    }

    private static EncryptedBlock blockFor(Class<?> host, int idx) {
        String k = key(host, idx);
        EncryptedBlock existing = SLOTS.get(k);
        if (existing != null) return existing;

        // The first invocation: build the block from RUGUARD_BLOCKS.
        EncryptedBlock built = buildSlotFromClass(host, idx);
        if (built == null) return null;
        SLOTS.putIfAbsent(k, built);
        return SLOTS.get(k);
    }

    private static EncryptedBlock buildSlotFromClass(Class<?> host, int idx) {
        try {
            Field_Blocks fb = Field_Blocks.of(host);
            if (fb == null) {
                return null;
            }

            // Ensure RuntimeContext is initialized before decrypting
            try {
                RuntimeContext.current();
            } catch (IllegalStateException e) {
                io.ruguard.annotation.RuguardProtected rp = host.getAnnotation(io.ruguard.annotation.RuguardProtected.class);
                if (rp != null) {
                    String seedHex = rp.buildSeedHex();
                    if (seedHex != null && seedHex.length() == 16) {
                        byte[] seed = hexToBytes(seedHex);
                        RuntimeContext.initialise(seed);
                    } else {
                        System.err.println("[BlockDispatcher] RuguardProtected buildSeedHex is invalid or empty for " + host.getName());
                    }
                } else {
                    System.err.println("[BlockDispatcher] RuguardProtected annotation is missing for " + host.getName());
                }
            }

            return new EncryptedBlock(idx, fb.bytesAt(idx), fb.lookup());
        } catch (Throwable t) {
            System.err.println("[BlockDispatcher] buildSlotFromClass failed for " + host.getName() + " index " + idx + ": " + t);
            t.printStackTrace();
            return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private static Class<?> returnTypeOf(Class<?> host, int idx) {
        for (Method m : host.getDeclaredMethods()) {
            GuardedStub gs = m.getAnnotation(GuardedStub.class);
            if (gs != null && gs.blockIndex() == idx) return m.getReturnType();
        }
        return void.class;
    }

    /** Collision-free key: fully qualified class name + block index. */
    private static String key(Class<?> host, int idx) {
        return host.getName() + "#" + idx;
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == void.class  ) return null;
        if (type == int.class   ) return 0;
        if (type == long.class  ) return 0L;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == double.class) return 0.0;
        if (type == float.class ) return 0f;
        if (type == short.class ) return (short) 0;
        if (type == byte.class  ) return (byte) 0;
        if (type == char.class  ) return '\0';
        return null;
    }

    /** Tiny reflection helper that pulls {@code RUGUARD_BLOCKS} from a host
     *  class, plus the corresponding {@code MethodHandles.Lookup} it ships
     *  with. The host class has these generated for it by the CLI. */
    public static final class Field_Blocks {
        private final Object array;       // byte[][] actually — RUGUARD_BLOCKS
        private final MethodHandles.Lookup lookup;

        private Field_Blocks(Object array, MethodHandles.Lookup lookup) {
            this.array = array;
            this.lookup = lookup;
        }

        public static Field_Blocks of(Class<?> host) {
            try {
                java.lang.reflect.Field blocks = host.getDeclaredField("RUGUARD_BLOCKS");
                blocks.setAccessible(true);
                Object arr = blocks.get(null);
                java.lang.reflect.Field lkp = host.getDeclaredField("RUGUARD_LOOKUP");
                lkp.setAccessible(true);
                MethodHandles.Lookup l = (MethodHandles.Lookup) lkp.get(null);
                return new Field_Blocks(arr, l);
            } catch (Throwable t) {
                System.err.println("[BlockDispatcher] Field_Blocks.of failed for " + host.getName() + ": " + t);
                t.printStackTrace();
                return null;
            }
        }

        public byte[][] blocksArray() {
            return (byte[][]) array;
        }

        public byte[] bytesAt(int idx) {
            return ((byte[][]) array)[idx];
        }

        public MethodHandles.Lookup lookup() {
            return lookup;
        }
    }
}
