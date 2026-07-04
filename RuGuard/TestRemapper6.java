import io.ruguard.cli.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

public class TestRemapper6 {
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
        
        // 1. Get original class
        byte[] originalBytes = classes.get("code/cataclysm/features/module/setting/implement/SliderSettings.class");
        
        // 2. Transform using ClassProtector
        byte[] seed = new byte[8];
        byte[] secret = new byte[32];
        ClassProtector protector = new ClassProtector(seed, secret, "code/cataclysm/", io.ruguard.annotation.Guarded.Level.STRONG);
        protector.setNameRemapper(remapper);
        byte[] transformedBytes = protector.transform(originalBytes);
        
        // 3. Remap using ClassRemapper
        org.objectweb.asm.commons.Remapper asmRemapper = remapper.createRemapper();
        ClassReader cr = new ClassReader(transformedBytes);
        ClassWriter cw = new ClassWriter(0);
        ClassRemapper classRemapper = new ClassRemapper(cw, asmRemapper);
        cr.accept(classRemapper, ClassReader.EXPAND_FRAMES);
        byte[] finalBytes = cw.toByteArray();
        
        // 4. Check instructions
        ClassNode cn = new ClassNode();
        new ClassReader(finalBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("visible") || mn.name.equals("iIllili") || mn.name.equals("iiillIIi")) { // check remapped name too
                System.out.println("Method: " + mn.name);
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        System.out.println("  Mapped call in visible: " + minsn.owner + "." + minsn.name + minsn.desc);
                    }
                }
            }
        }
    }
}
