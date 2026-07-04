import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

public class ExtractMixin {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("code/cataclysm/mixins/TextVisitFactoryMixin.class");
            if (entry == null) {
                System.out.println("TextVisitFactoryMixin not found!");
                return;
            }
            try (InputStream is = jar.getInputStream(entry)) {
                Files.copy(is, Paths.get("D:\\Protection\\RuGuard\\TextVisitFactoryMixin.class"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Extracted successfully.");
            }
        }
    }
}
