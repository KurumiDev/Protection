import io.ruguard.cli.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public class TestRemapperResolve4 {
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile inputJar = new JarFile("D:\\Protection\\RuGuard\\cataclysm-2.0-DEV (2).jar")) {
            Enumeration<JarEntry> entries = inputJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream is = inputJar.getInputStream(entry)) {
                        classes.put(entry.getName(), is.readAllBytes());
                    }
                }
            }
        }
        NameRemapper remapper = new NameRemapper("code/cataclysm", Collections.emptyList());
        remapper.analyze(classes);
        
        org.objectweb.asm.commons.Remapper asmRemapper = remapper.createRemapper();
        for (String className : classes.keySet()) {
            String original = className.replace(".class", "");
            String mapped = asmRemapper.map(original);
            if ("a/b/llllIIl".equals(mapped)) {
                System.out.println("a.b.llllIIl is: " + original);
            }
        }
    }
}
