package io.ruguard.nativebridge;

public final class LibraryLoader {
    private static boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;
        try {
            System.loadLibrary("ruguard_native");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError e) {
            // Fallback to resource extraction
        }

        try {
            java.io.InputStream in = LibraryLoader.class.getResourceAsStream("/io/ruguard/nativebridge/ruguard_native.dll");
            if (in == null) {
                // Try classpath resource without leading slash
                in = LibraryLoader.class.getClassLoader().getResourceAsStream("io/ruguard/nativebridge/ruguard_native.dll");
            }
            
            if (in == null) {
                // Direct JAR extraction bypassing classloader limitations
                try {
                    java.security.CodeSource cs = LibraryLoader.class.getProtectionDomain().getCodeSource();
                    if (cs != null && cs.getLocation() != null) {
                        java.net.URL loc = cs.getLocation();
                        java.io.File jarFile = null;
                        if ("file".equalsIgnoreCase(loc.getProtocol())) {
                            jarFile = new java.io.File(loc.toURI());
                        } else {
                            String path = loc.getPath();
                            if (path.startsWith("file:")) {
                                int excl = path.indexOf('!');
                                if (excl != -1) {
                                    path = path.substring(5, excl);
                                } else {
                                    path = path.substring(5);
                                }
                                jarFile = new java.io.File(new java.net.URI(path));
                            }
                        }
                        if (jarFile != null && jarFile.exists() && jarFile.isFile()) {
                            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarFile);
                            java.util.zip.ZipEntry entry = zip.getEntry("io/ruguard/nativebridge/ruguard_native.dll");
                            if (entry != null) {
                                in = zip.getInputStream(entry);
                            } else {
                                zip.close();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            
            if (in == null) {
                // Fallback to user directory
                java.io.File localFile = new java.io.File(System.getProperty("user.dir") + "/ruguard_native.dll");
                if (localFile.exists()) {
                    System.load(localFile.getAbsolutePath());
                    loaded = true;
                    return;
                }
                throw new java.io.FileNotFoundException("Could not find ruguard_native.dll in resources or JAR");
            }
            
            // Neutral, randomised temp name
            java.io.File temp = java.io.File.createTempFile(
                    "jni" + Long.toHexString(System.nanoTime() & 0xffffffffL), ".dll");
            temp.deleteOnExit();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            System.load(temp.getAbsolutePath());
            loaded = true;
        } catch (Throwable t) {
            System.out.println("[LibraryLoader] Failed to load native library:");
            t.printStackTrace(System.out);
        }
    }
}
