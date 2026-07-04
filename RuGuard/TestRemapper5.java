import io.ruguard.cli.NameRemapper;
import java.util.Map;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

public class TestRemapper5 {
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
        
        org.objectweb.asm.commons.Remapper asmRemapper = remapper.createRemapper();
        byte[] particlesBytes = classes.get("code/cataclysm/features/impl/render/Particles.class");
        
        ClassReader cr = new ClassReader(particlesBytes);
        ClassWriter cw = new ClassWriter(0);
        ClassRemapper classRemapper = new ClassRemapper(cw, asmRemapper);
        cr.accept(classRemapper, ClassReader.EXPAND_FRAMES);
        byte[] remappedParticles = cw.toByteArray();
        
        ClassNode cn = new ClassNode();
        new ClassReader(remappedParticles).accept(cn, 0);
        
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>")) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        if (minsn.desc.contains("Supplier")) {
                            System.out.println("Mapped call in <init>: " + minsn.owner + "." + minsn.name + minsn.desc);
                        }
                    }
                }
            }
        }
    }
}
