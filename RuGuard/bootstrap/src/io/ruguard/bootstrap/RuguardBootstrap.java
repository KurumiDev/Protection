package io.ruguard.bootstrap;

import io.ruguard.runtime.RuguardClassLoader;
import io.ruguard.runtime.RuntimeContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;

/**
 * Entry point that loads a {@code :ruguard-protected} jar and runs its
 * {@code Main-Class}. Treat this as a thin shim around
 * {@code java -cp}; the interesting work is in {@link RuguardClassLoader}.
 *
 * <p>Usage: {@code java -jar ruguard-bootstrap.jar <protected.jar> [args...]}
 *
 * <p>Spec refs: §3 ("ручной JNI / jni-rs, не JNA"), §10 (custom JRE) — this
 * class is the precursor to the standalone launcher described in §9.2.
 */
public final class RuguardBootstrap {

    private RuguardBootstrap() {}

    public static void main(String[] args) throws Exception {
        Path jarPath = null;
        String[] forwardedArgs = args;

        // 1. Try to find if we are running directly from a JAR file
        try {
            java.net.URI uri = RuguardBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                jarPath = path;
            }
        } catch (Exception ignored) {}

        // 2. If not running from a JAR, or if we want to fallback to the old behavior:
        if (jarPath == null && args.length >= 1) {
            Path path = Paths.get(args[0]).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                jarPath = path;
                forwardedArgs = new String[args.length - 1];
                System.arraycopy(args, 1, forwardedArgs, 0, forwardedArgs.length);
            }
        }

        if (jarPath == null) {
            System.err.println("Usage: java -jar ruguard-bootstrap.jar <protected.jar> [args...] OR run a standalone protected JAR");
            System.exit(2);
        }

        try (RuguardClassLoader loader = new RuguardClassLoader(new JarFile(jarPath.toFile()))) {
            if (loader.isProtected()) {
                // RuntimeContext was initialised in the loader constructor.
                RuntimeContext.current().installNativeEntropyHook();
            }
            String mainClassName = mainClass(loader, jarPath);
            Class<?> mainClass = loader.loadClass(mainClassName);
            Method main = mainClass.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) forwardedArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw ite;
            }
        } catch (IOException e) {
            System.err.println("Failed to open jar: " + e);
            System.exit(1);
        } finally {
            RuntimeContext ctx = null;
            try {
                ctx = RuntimeContext.current();
            } catch (IllegalStateException ignored) {
                // never initialised — that's fine, no cleanup needed
            }
            if (ctx != null) ctx.shutdown();
        }
    }

    private static String mainClass(RuguardClassLoader loader, Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            String mc = jf.getManifest().getMainAttributes().getValue("RUGUARD_MAIN_CLASS");
            if (mc == null) {
                mc = jf.getManifest().getMainAttributes().getValue("Main-Class");
            }
            if (mc == null || mc.equals("io.ruguard.bootstrap.RuguardBootstrap")) {
                System.err.println("Jar has no valid main class attribute: " + jar);
                System.exit(2);
            }
            return mc;
        }
    }
}
