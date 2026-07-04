import io.ruguard.cli.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import java.util.function.Consumer;

public class TestRemapper7 {
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
        
        byte[] originalBytes = classes.get("code/cataclysm/features/module/setting/implement/SliderSettings.class");
        
        byte[] seed = new byte[8];
        byte[] secret = new byte[32];
        ClassProtector protector = new ClassProtector(seed, secret, "code/cataclysm/", io.ruguard.annotation.Guarded.Level.STRONG);
        protector.setNameRemapper(remapper);
        
        List<byte[]> blocks = new ArrayList<>();
        // Note: we can't easily intercept setBlockCallback if we didn't expose it, 
        // but we can just use reflection or read the block field from the object.
        // Actually, we don't have blockCallback, but we can intercept synthClasses
        java.lang.reflect.Field f = ClassProtector.class.getDeclaredField("synthClasses");
        f.setAccessible(true);
        List<byte[]> synthClasses = (List<byte[]>) f.get(protector);
        
        protector.transform(originalBytes);
        
        for (byte[] block : synthClasses) {
            ClassNode cn = new ClassNode();
            new ClassReader(block).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("$exec")) {
                    System.out.println("Checking block: " + cn.name);
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode minsn = (MethodInsnNode) insn;
                            System.out.println("  Method call in $exec: " + minsn.owner + "." + minsn.name + minsn.desc);
                        }
                    }
                }
            }
        }
    }
}
