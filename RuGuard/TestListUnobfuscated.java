import java.util.jar.*;
import java.io.*;
import java.util.Enumeration;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class TestListUnobfuscated {
    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar")) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    ClassReader cr = new ClassReader(is);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_CODE);
                    if (cn.name.startsWith("code/cataclysm/")) {
                        System.out.println("Unobfuscated: " + cn.name);
                    }
                }
            }
        }
    }
}
