import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FindStringInCP {
    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile("D:\\Protection\\RuGuard\\cataclysm-2.0-DEV (2).jar")) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    byte[] data = new byte[16384];
                    int nRead;
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    byte[] classBytes = buffer.toByteArray();
                    String s = new String(classBytes, "ISO-8859-1");
                    if (s.contains("setVisible")) {
                        System.out.println("Contains setVisible: " + entry.getName());
                    }
                }
            }
        }
    }
}
