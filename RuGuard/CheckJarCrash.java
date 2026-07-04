import java.util.jar.*;
import java.util.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class CheckJarCrash {
    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar")) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    ClassReader cr = new ClassReader(is);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    for (MethodNode mn : cn.methods) {
                        for (AbstractInsnNode ain : mn.instructions) {
                            if (ain instanceof MethodInsnNode) {
                                MethodInsnNode min = (MethodInsnNode) ain;
                                if (min.name.equals("setVisible")) {
                                    System.out.println("FOUND setVisible call in " + cn.name + "." + mn.name + ": " + min.owner + "." + min.name + min.desc);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
