import io.ruguard.cli.NameRemapper;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class TestRemapper {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile("D:\\Protection\\RuGuard\\cataclysm-2.0-DEV (2).jar")) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")) {
                    InputStream is = jar.getInputStream(entry);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[16384];
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    classes.put(entry.getName(), buffer.toByteArray());
                }
            }
        }
        NameRemapper remapper = new NameRemapper("code/cataclysm/", java.util.Collections.emptyList());
        remapper.analyze(classes);
        Map<String, String> mapping = remapper.getMapping();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (e.getKey().contains("setVisible") || e.getValue().equals("llllIIl") || e.getValue().equals("iiiiii")) {
                System.out.println(e.getKey() + " -> " + e.getValue());
            }
        }
    }
}
