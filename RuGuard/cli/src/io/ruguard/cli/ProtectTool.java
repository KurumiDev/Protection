package io.ruguard.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.jar.*;

/**
 * CLI tool to protect a JAR file using RuGuard bytecode transformation.
 *
 * Usage:
 *   java -cp "..." io.ruguard.cli.ProtectTool <input.jar> <output.jar> [--seed <hex16>] [--secret <hex64>]
 *
 * Options:
 *   --seed   16 hex characters (8 bytes) used as the transformation seed.
 *   --secret 64 hex characters (32 bytes) used as the encryption secret.
 *
 * If seed or secret are omitted, cryptographically random values are generated.
 */
public class ProtectTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: ProtectTool <input.jar> <output.jar> [--seed <hex16>] [--secret <hex64>]");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];

        byte[] seed = null;
        byte[] secret = null;
        String protectPackage = null;
        io.ruguard.annotation.Guarded.Level protectLevel = io.ruguard.annotation.Guarded.Level.STRONG;

        // Parse optional arguments
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--seed":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: --seed requires a 16-character hex value");
                        System.exit(1);
                    }
                    seed = hexToBytes(args[++i], 8, "--seed");
                    break;
                case "--secret":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: --secret requires a 64-character hex value");
                        System.exit(1);
                    }
                    secret = hexToBytes(args[++i], 32, "--secret");
                    break;
                case "--protect-package":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: --protect-package requires a package prefix (e.g. code/cataclysm/)");
                        System.exit(1);
                    }
                    protectPackage = args[++i];
                    break;
                case "--protect-level":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: --protect-level requires a level (STANDARD, STRONG, VIRTUALIZED)");
                        System.exit(1);
                    }
                    try {
                        protectLevel = io.ruguard.annotation.Guarded.Level.valueOf(args[++i].toUpperCase());
                    } catch (Exception e) {
                        System.err.println("Error: Invalid level: " + args[i] + ". Choose from: STANDARD, STRONG, VIRTUALIZED");
                        System.exit(1);
                    }
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(1);
            }
        }

        // Generate random values if not provided
        SecureRandom rng = new SecureRandom();
        if (seed == null) {
            seed = new byte[8];
            rng.nextBytes(seed);
        }
        if (secret == null) {
            secret = new byte[32];
            rng.nextBytes(secret);
        }

        System.out.println("RuGuard ProtectTool");
        System.out.println("  Input:  " + inputPath);
        System.out.println("  Output: " + outputPath);
        System.out.println("  Seed:   " + bytesToHex(seed));
        System.out.println("  Secret: " + bytesToHex(secret));
        if (protectPackage != null) {
            System.out.println("  Auto-Protect Package: " + protectPackage);
            System.out.println("  Auto-Protect Level:   " + protectLevel);
        }
        System.out.println();

        ClassProtector protector = new ClassProtector(seed, secret, protectPackage, protectLevel);

        int scanned = 0;
        int transformed = 0;

        // Ensure output parent directory exists
        Path outputFile = Paths.get(outputPath);
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        Set<String> existingEntries = new HashSet<>();
        try (JarFile inputJar = new JarFile(inputPath);
             JarOutputStream jos = createOutputJar(inputJar, outputPath, seed)) {

            Enumeration<JarEntry> entries = inputJar.entries();
            // Pass 1: Collect classes and map names
            Map<String, byte[]> classes = new HashMap<>();
            byte[] fabricJson = null;
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;

                byte[] data;
                try (InputStream is = inputJar.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                
                if (name.endsWith(".class")) {
                    classes.put(name, data);
                } else {
                    if (name.equals("fabric.mod.json")) {
                        fabricJson = data;
                    }
                    if (existingEntries.add(name)) {
                        JarEntry outEntry = new JarEntry(name);
                        outEntry.setTime(entry.getTime());
                        jos.putNextEntry(outEntry);
                        jos.write(data);
                        jos.closeEntry();
                    }
                }
            }
            
            // Collect dependencies (io.ruguard.*, BouncyCastle) into classes map
            collectDependencies(classes);
            
            // Extract entry points
            List<String> entryPoints = new ArrayList<>();
            if (fabricJson != null) {
                String json = new String(fabricJson, java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+)\"").matcher(json);
                while (m.find()) {
                    entryPoints.add(m.group(1));
                }
            }
            
            // Pass 2: Apply obfuscation
            StringEncryptor stringEncryptor = new StringEncryptor(seed);
            AntiDecompiler antiDecompiler = new AntiDecompiler(seed);
            
            NameRemapper remapper = null;
            if (protectPackage != null) {
                remapper = new NameRemapper(protectPackage, entryPoints);
                remapper.analyze(classes);
            }

            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                String name = entry.getKey();
                byte[] data = entry.getValue();
                scanned++;

                boolean isAutoProtected = protectPackage != null && name.startsWith(protectPackage) && !name.contains("mixin");

                // 1. String Encryption (Apply if auto-protected)
                boolean modified = false;
                if (isAutoProtected) {
                    byte[] seBytes = stringEncryptor.encrypt(data);
                    if (seBytes != null) {
                        data = seBytes;
                        modified = true;
                    }
                }

                // 2. Class Protection (Checks @Guarded or auto-protects based on prefix)
                byte[] cpBytes = protector.transform(data);
                boolean isProtected = false;
                if (cpBytes != null) {
                    data = cpBytes;
                    isProtected = true;
                    modified = true;
                    transformed++;
                    System.out.println("  [PROTECTED] " + name);
                } else if (modified) {
                    transformed++;
                    System.out.println("  [STRINGS ONLY] " + name);
                } else {
                    System.out.println("  [SKIPPED]   " + name);
                }

                // 3. Remapping (Must run BEFORE Anti-Decompiler so ClassRemapper doesn't crash on corrupted signatures)
                if (remapper != null) {
                    data = remapper.remap(data);
                }

                // 4. Anti-Decompiler (Only apply if class has Guarded methods or is auto-protected)
                if (isProtected || isAutoProtected) {
                    data = antiDecompiler.apply(data);
                }
                
                String newName = name;
                
                // Determine actual name from the bytes (which were already remapped)
                if (remapper != null) {
                     ClassReader cr = new ClassReader(data);
                     ClassNode cn = new ClassNode();
                     cr.accept(cn, ClassReader.SKIP_CODE);
                     newName = cn.name + ".class";
                }

                if (existingEntries.add(newName)) {
                    JarEntry outEntry = new JarEntry(newName);
                    // outEntry.setTime(...) omit time for stealth
                    jos.putNextEntry(outEntry);
                    jos.write(data);
                    jos.closeEntry();
                }
            }
            
            // 5. Inject Trash Classes
            TrashInjector trashInjector = new TrashInjector(seed);
            Map<String, byte[]> trashClasses = trashInjector.generate(50);
            for (Map.Entry<String, byte[]> tc : trashClasses.entrySet()) {
                String tcName = tc.getKey() + ".class";
                if (existingEntries.add(tcName)) {
                    JarEntry outEntry = new JarEntry(tcName);
                    jos.putNextEntry(outEntry);
                    jos.write(tc.getValue());
                    jos.closeEntry();
                }
            }

            // Copy native DLL since it wasn't collected into classes
            copyNativeDll(jos, existingEntries);
        }

        System.out.println();
        System.out.println("=== PROTECTION SUMMARY ===");
        System.out.println("Classes scanned:     " + scanned);
        System.out.println("Classes transformed: " + transformed);
        System.out.println("Output JAR:          " + outputPath);
        System.out.println();
        System.out.println("Reproducibility keys (save these!):");
        System.out.println("  --seed   " + bytesToHex(seed));
        System.out.println("  --secret " + bytesToHex(secret));
    }

    /**
     * Creates a JarOutputStream with a manifest that preserves the original
     * Main-Class in RUGUARD_MAIN_CLASS and sets Main-Class to RuguardBootstrap.
     */
    private static JarOutputStream createOutputJar(JarFile inputJar, String outputPath, byte[] seed)
            throws IOException {
        Manifest originalManifest = inputJar.getManifest();
        Manifest newManifest = new Manifest();

        Attributes mainAttrs = newManifest.getMainAttributes();
        mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        String originalMainClass = null;

        // Preserve original manifest attributes
        if (originalManifest != null) {
            Attributes origAttrs = originalManifest.getMainAttributes();
            for (Map.Entry<Object, Object> entry : origAttrs.entrySet()) {
                Object key = entry.getKey();
                // Skip Manifest-Version (already set)
                if (Attributes.Name.MANIFEST_VERSION.equals(key)) continue;
                if (Attributes.Name.MAIN_CLASS.equals(key)) {
                    originalMainClass = (String) entry.getValue();
                }
                mainAttrs.put(key, entry.getValue());
            }
        }

        // Add RuGuard markers and hijack Main-Class
        if (originalMainClass != null) {
            mainAttrs.putValue("RUGUARD_MAIN_CLASS", originalMainClass);
        }
        mainAttrs.put(Attributes.Name.MAIN_CLASS, "io.ruguard.bootstrap.RuguardBootstrap");
        mainAttrs.putValue("RUGUARD_PROTECTED", "true");
        mainAttrs.putValue("RUGUARD_BUILD_SEED", bytesToHex(seed));

        return new JarOutputStream(new FileOutputStream(outputPath), newManifest);
    }

    /**
     * Parses a hex string into a byte array, validating the expected length.
     */
    private static byte[] hexToBytes(String hex, int expectedBytes, String paramName) {
        int expectedLen = expectedBytes * 2;
        if (hex.length() != expectedLen) {
            System.err.println("Error: " + paramName + " must be exactly "
                    + expectedLen + " hex characters (got " + hex.length() + ")");
            System.exit(1);
        }
        byte[] bytes = new byte[expectedBytes];
        for (int i = 0; i < expectedBytes; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                System.err.println("Error: " + paramName + " contains invalid hex characters");
                System.exit(1);
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    /**
     * Converts a byte array to a lowercase hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static void collectDependencies(Map<String, byte[]> classes) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String[] entries = classpath.split(File.pathSeparator);
        
        for (String entryPath : entries) {
            File entryFile = new File(entryPath).getAbsoluteFile();
            if (!entryFile.exists()) continue;
            
            if (entryFile.isDirectory()) {
                collectDirEntry(entryFile, entryFile, classes);
            } else if (entryFile.isFile() && entryFile.getName().endsWith(".jar")) {
                String nameLower = entryFile.getName().toLowerCase();
                if (nameLower.contains("bcprov") || nameLower.contains("bouncycastle")) {
                    collectJarEntry(entryFile, classes);
                }
            }
        }
    }

    private static void collectDirEntry(File baseDir, File currentDir, Map<String, byte[]> classes) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectDirEntry(baseDir, f, classes);
            } else if (f.isFile() && f.getName().endsWith(".class")) {
                String relPath = baseDir.toURI().relativize(f.toURI()).getPath();
                relPath = relPath.replace('\\', '/');
                
                if (relPath.startsWith("io/ruguard/runtime/") ||
                    relPath.startsWith("io/ruguard/nativebridge/") ||
                    relPath.startsWith("io/ruguard/bootstrap/") ||
                    relPath.startsWith("io/ruguard/annotation/") ||
                    relPath.startsWith("org/bouncycastle/")) {
                    
                    try (InputStream in = new FileInputStream(f)) {
                        classes.put(relPath, in.readAllBytes());
                    }
                }
            }
        }
    }

    private static void collectJarEntry(File jarFile, Map<String, byte[]> classes) throws IOException {
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                
                if (entry.isDirectory() || !name.endsWith(".class") || 
                    name.toUpperCase().startsWith("META-INF/") || 
                    name.equals("module-info.class")) {
                    continue;
                }
                
                try (InputStream in = jf.getInputStream(entry)) {
                    classes.put(name, in.readAllBytes());
                }
            }
        }
    }

    private static void copyNativeDll(JarOutputStream jos, Set<String> existingEntries) throws IOException {
        String targetEntry = "io/ruguard/nativebridge/ruguard_native.dll";
        if (!existingEntries.add(targetEntry)) {
            return;
        }
        
        File[] paths = {
            new File("ruguard_native.dll"),
            new File("out/ruguard_native.dll"),
            new File("native/barrier/target/release/ruguard_native.dll"),
            new File("../ruguard_native.dll"),
            new File("../../ruguard_native.dll")
        };
        
        File dllFile = null;
        for (File p : paths) {
            if (p.exists() && p.isFile()) {
                dllFile = p;
                break;
            }
        }
        
        if (dllFile != null) {
            System.out.println("  [SHADING] Bundling native DLL from: " + dllFile.getAbsolutePath());
            JarEntry entry = new JarEntry(targetEntry);
            entry.setTime(dllFile.lastModified());
            jos.putNextEntry(entry);
            try (InputStream in = new FileInputStream(dllFile)) {
                in.transferTo(jos);
            }
            jos.closeEntry();
        } else {
            try (InputStream in = ProtectTool.class.getResourceAsStream("/ruguard_native.dll")) {
                if (in != null) {
                    System.out.println("  [SHADING] Bundling native DLL from classpath resource");
                    JarEntry entry = new JarEntry(targetEntry);
                    jos.putNextEntry(entry);
                    in.transferTo(jos);
                    jos.closeEntry();
                    return;
                }
            }
            System.err.println("RuGuard [WARN]: ruguard_native.dll not found in expected paths or classpath!");
        }
    }
}
