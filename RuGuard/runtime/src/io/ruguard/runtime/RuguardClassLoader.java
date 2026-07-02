package io.ruguard.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Minimal class-loader that locates classes inside a {@code .jar} file. It
 * exists so that the bootstrap main can launch a {@code :ruguard-protected}
 * jar without requiring a separate launcher.
 *
 * <p>Loading order:
 * <ol>
 *   <li>{@code RUGUARD_PROTECTED} entry on the jar's manifest — if absent,
 *       the loader is identical to {@code URLClassLoader} on the same jar
 *       (i.e. we fall back gracefully);</li>
 *   <li>otherwise the loader treats every class as protected, instantiates
 *       {@link RuntimeContext} with the {@code RUGUARD_BUILD_SEED} attribute
 *       from the manifest, and routes loads through
 *       {@link #defineClassLocked(String, byte[])}.</li>
 * </ol>
 *
 * <p>Spec refs: §5 ("уже есть слой"), §6 ("chain of custody between class
 * file and runtime decoder"), §8.3 (integrity checks piggyback on the
 * loader).
 */
public final class RuguardClassLoader extends SecureClassLoader implements java.io.Closeable {

    private final JarFile jar;
    private final boolean protected_;
    private final Map<String, Class<?>> defined = new HashMap<>();
    /** Cached build seed from the jar manifest. */
    private final byte[] buildSeed = new byte[8];

    public RuguardClassLoader(JarFile jar) throws IOException {
        super(RuguardClassLoader.class.getClassLoader());
        this.jar = jar;
        this.protected_ = jar.getManifest() != null
                && "true".equalsIgnoreCase(jar.getManifest().getMainAttributes().getValue("RUGUARD_PROTECTED"));
        if (protected_) {
            String seedHex = jar.getManifest().getMainAttributes().getValue("RUGUARD_BUILD_SEED");
            if (seedHex != null && seedHex.length() >= 16) {
                for (int i = 0; i < 8; i++) {
                    int hi = Character.digit(seedHex.charAt(i * 2), 16);
                    int lo = Character.digit(seedHex.charAt(i * 2 + 1), 16);
                    this.buildSeed[i] = (byte) ((hi << 4) | lo);
                }
            } else {
                // generate a fallback seed from process identity; this path
                // only triggers if the manifest was hand-edited
                long v = (System.nanoTime() ^ ProcessHandle.current().pid());
                for (int i = 0; i < 8; i++) {
                    this.buildSeed[i] = (byte) (v >>> (56 - i * 8));
                }
            }
        }
        if (protected_) {
            RuntimeContext.initialise(buildSeed);
        }
    }

    public boolean isProtected() {
        return protected_;
    }

    public byte[] buildSeed() {
        return buildSeed;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> existing = defined.get(name);
        if (existing != null) return existing;
        String resource = name.replace('.', '/') + ".class";
        JarEntry entry = jar.getJarEntry(resource);
        if (entry == null) throw new ClassNotFoundException(name);
        byte[] bytes;
        try (InputStream in = jar.getInputStream(entry)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        return defineClassLocked(name, bytes);
    }

    /** Atomic defineClass; on collision returns the previously-defined class. */
    private synchronized Class<?> defineClassLocked(String name, byte[] bytes) {
        Class<?> existing = defined.get(name);
        if (existing != null) return existing;
        Class<?> c = defineClass(name, bytes, 0, bytes.length,
                RuguardClassLoader.class.getProtectionDomain());
        defined.put(name, c);
        return c;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (protected_ && isApplicationClass(name)) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // ignore and delegate
                    }
                }
                if (c == null) {
                    c = super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private boolean isApplicationClass(String name) {
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.") || name.startsWith("jdk.")) {
            return false;
        }
        if (name.startsWith("io.ruguard.runtime.") || name.startsWith("io.ruguard.nativebridge.") || 
            name.startsWith("io.ruguard.bootstrap.") || name.startsWith("io.ruguard.annotation.")) {
            return false;
        }
        if (name.startsWith("org.bouncycastle.")) {
            return false;
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }
}
