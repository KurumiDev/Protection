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
                // Fallback to user directory
                System.load(System.getProperty("user.dir") + "/ruguard_native.dll");
                loaded = true;
                return;
            }
            // Neutral, randomised temp name — never embed the product/library
            // name on disk where it advertises the protection layer.
            java.io.File temp = java.io.File.createTempFile(
                    "jni" + Long.toHexString(System.nanoTime() & 0xffffffffL), ".dll");
            temp.deleteOnExit();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            System.load(temp.getAbsolutePath());
            loaded = true;
        } catch (Throwable t) {
            // Silent: a stderr warning naming the library confirms both the
            // product and the presence of a native barrier. The runtime falls
            // back to Java-only entropy, which quietly yields wrong keys (P4).
        }
    }
}
