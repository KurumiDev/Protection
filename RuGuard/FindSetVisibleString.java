import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.InputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class FindSetVisibleString {
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
                            if (insn instanceof LdcInsnNode) {
                                Object cst = ((LdcInsnNode) insn).cst;
                                if (cst instanceof String) {
                                    if ("setVisible".equals(cst)) {
                                        System.out.println("FOUND STRING in " + cn.name + "." + mn.name);
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
