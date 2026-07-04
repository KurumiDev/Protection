import java.util.jar.*;
import java.io.*;
import java.util.Enumeration;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class TestCheckJar {
    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar")) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().contains("SliderSettings") || entry.getName().contains("Particles")) {
                    System.out.println("Entry: " + entry.getName());
                }
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    ClassReader cr = new ClassReader(is);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_CODE);
                    if (cn.name.contains("SliderSettings") || cn.name.contains("Particles")) {
                        System.out.println("ClassNode name: " + cn.name);
                    }
                }
            }
        }
    }
}
