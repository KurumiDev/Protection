import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.InputStream;

public class FindIndySetVisible {
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
                            if (insn instanceof InvokeDynamicInsnNode) {
                                InvokeDynamicInsnNode idin = (InvokeDynamicInsnNode) insn;
                                Object[] bsmArgs = idin.bsmArgs;
                                for (Object arg : bsmArgs) {
                                    if (arg instanceof org.objectweb.asm.Handle) {
                                        org.objectweb.asm.Handle h = (org.objectweb.asm.Handle) arg;
                                        if (h.getName().equals("setVisible") || h.getName().equals("visible")) {
                                            System.out.println("FOUND in " + cn.name + "." + mn.name + " -> Indy " + h.getOwner() + "." + h.getName());
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
