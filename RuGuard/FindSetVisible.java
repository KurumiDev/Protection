import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class FindSetVisible {
    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile(args[0])) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    InputStream is = jar.getInputStream(entry);
                    ClassReader cr = new ClassReader(is);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    for (MethodNode mn : cn.methods) {
                        for (AbstractInsnNode insn : mn.instructions) {
                            if (insn instanceof MethodInsnNode) {
                                MethodInsnNode min = (MethodInsnNode) insn;
                                if (min.name.equals("setVisible") || min.name.equals("visible")) {
                                    System.out.println("FOUND in " + cn.name + "." + mn.name + " -> " + min.owner + "." + min.name + min.desc);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
