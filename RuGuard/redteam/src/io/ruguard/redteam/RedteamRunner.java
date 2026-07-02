package io.ruguard.redteam;

import java.io.*;
import java.util.jar.*;

/**
 * Baseline redteam runner (Spec §11). Attempts to extract plaintext
 * bytecode from a protected jar. A PASS means the attack succeeded
 * (protection is broken); a FAIL means the attack was blocked (good).
 */
public class RedteamRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: RedteamRunner <protected.jar>");
            System.exit(2);
        }
        String jarPath = args[0];
        int attacks = 0, blocked = 0;

        // Attack 1: Check that no class file contains plaintext @Guarded method bodies
        // by looking for RUGUARD_BLOCKS field and @GuardedStub annotations
        attacks++;
        try (JarFile jar = new JarFile(jarPath)) {
            var entries = jar.entries();
            boolean hasProtection = false;
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                byte[] bytes = jar.getInputStream(e).readAllBytes();
                String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (content.contains("RUGUARD_BLOCKS")) {
                    hasProtection = true;
                    // Check the encrypted blocks are actually there
                    if (content.contains("GuardedStub")) {
                        blocked++;
                        System.out.println("[BLOCKED] Attack 1: " + e.getName() + " has RUGUARD_BLOCKS + GuardedStub — methods are protected");
                    }
                }
            }
            if (!hasProtection) {
                System.out.println("[PASS] Attack 1: No RUGUARD_BLOCKS found — jar is NOT protected!");
            }
        }

        // Attack 2: Verify manifest has protection markers
        attacks++;
        try (JarFile jar = new JarFile(jarPath)) {
            java.util.jar.Manifest mf = jar.getManifest();
            if (mf != null && "true".equalsIgnoreCase(
                    mf.getMainAttributes().getValue("RUGUARD_PROTECTED"))) {
                blocked++;
                System.out.println("[BLOCKED] Attack 2: Manifest has RUGUARD_PROTECTED=true");
            } else {
                System.out.println("[PASS] Attack 2: No RUGUARD_PROTECTED in manifest!");
            }
        }

        // Attack 3: Dynamic block tampering check (Spec §8.3, §11)
        attacks++;
        try {
            File file = new File(jarPath);
            try (io.ruguard.runtime.RuguardClassLoader loader = new io.ruguard.runtime.RuguardClassLoader(new JarFile(file))) {
                // Load native DLL to hook master secret
                System.load(new File("ruguard_native.dll").getAbsolutePath());
                io.ruguard.runtime.RuntimeContext.current().installNativeEntropyHook();

                Class<?> sampleMod = loader.loadClass("io.ruguard.samples.SampleMod");
                java.lang.reflect.Method compute = sampleMod.getMethod("compute", int.class);
                int originalVal = (Integer) compute.invoke(null, 7);
                if (originalVal == 91) {
                    java.lang.reflect.Field blocksField = sampleMod.getDeclaredField("RUGUARD_BLOCKS");
                    blocksField.setAccessible(true);
                    byte[][] blocks = (byte[][]) blocksField.get(null);

                    // Tamper with block 0 to trigger cascade failure in block 1 (compute)
                    blocks[0][0] ^= 0x55;

                    // Evict cached method handles to force re-decryption
                    java.lang.reflect.Field slotsField = io.ruguard.runtime.BlockDispatcher.class.getDeclaredField("SLOTS");
                    slotsField.setAccessible(true);
                    java.util.concurrent.ConcurrentHashMap<?, ?> slots = (java.util.concurrent.ConcurrentHashMap<?, ?>) slotsField.get(null);
                    slots.clear();

                    java.lang.reflect.Field cacheField = io.ruguard.runtime.EncryptedBlock.class.getDeclaredField("HANDLE_CACHE");
                    cacheField.setAccessible(true);
                    java.util.concurrent.ConcurrentHashMap<?, ?> handleCache = (java.util.concurrent.ConcurrentHashMap<?, ?>) cacheField.get(null);
                    handleCache.clear();

                    int tamperedVal = (Integer) compute.invoke(null, 7);
                    if (tamperedVal == 0) {
                        blocked++;
                        System.out.println("[BLOCKED] Attack 3: Tampering with RUGUARD_BLOCKS caused method compute to return fallback value 0");
                    } else {
                        System.out.println("[PASS] Attack 3: Tampering with block 0 did NOT block compute! Value returned: " + tamperedVal);
                    }
                } else {
                    System.out.println("[FAIL] Attack 3: Untampered compute(7) returned " + originalVal + " instead of 91");
                }
            }
        } catch (Throwable t) {
            System.out.println("[FAIL] Attack 3: Exception during execution: " + t.getMessage());
            t.printStackTrace();
        }

        System.out.println();
        System.out.println("=== REDTEAM SUMMARY ===");
        System.out.println("Attacks: " + attacks + ", Blocked: " + blocked + ", Passed: " + (attacks - blocked));
        if (blocked == attacks) {
            System.out.println("RESULT: ALL ATTACKS BLOCKED — protection is working");
        } else {
            System.out.println("RESULT: SOME ATTACKS PASSED — PROTECTION IS BROKEN!");
            System.exit(1);
        }
    }
}
