import io.ruguard.cli.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;

public class TestRemapperResolve2 {
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
        System.out.println("mapMethodName visible: " + asmRemapper.mapMethodName("code/cataclysm/features/module/setting/implement/SliderSettings", "visible", "(Ljava/util/function/Supplier;)Lcode/cataclysm/features/module/setting/implement/SliderSettings;"));
        System.out.println("mapMethodName setVisible: " + asmRemapper.mapMethodName("code/cataclysm/features/module/setting/implement/SliderSettings", "setVisible", "(Ljava/util/function/Supplier;)V"));
        
        System.out.println("SliderSettings.visible mapping: " + remapper.getMapping().get("code/cataclysm/features/module/setting/implement/SliderSettings.visible(Ljava/util/function/Supplier;)Lcode/cataclysm/features/module/setting/implement/SliderSettings;"));
        System.out.println("Setting.setVisible mapping: " + remapper.getMapping().get("code/cataclysm/features/module/setting/Setting.setVisible(Ljava/util/function/Supplier;)V"));
    }
}
