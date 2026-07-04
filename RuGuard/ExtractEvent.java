import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

public class ExtractEvent {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("a/b/llllIIl.class");
            if (entry != null) {
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, Paths.get("D:\\Protection\\RuGuard\\llllIIl.class"), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("Extracted successfully.");
                }
            } else {
                System.out.println("Not found");
            }
        }
    }
}
