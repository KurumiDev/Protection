package io.ruguard.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Renames classes, methods, and fields to short, meaningless names (e.g., a, b, c).
 */
public class NameRemapper {

    private final Map<String, String> mapping = new HashMap<>();
    private final Set<String> keepClasses = new HashSet<>();
    private final Set<String> keepMethods = new HashSet<>(Arrays.asList(
            "<init>", "<clinit>", "main", "onInitialize", "onInitializeClient", "onInitializeServer"
    ));

    private final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private int classCounter = 0;
    private int methodCounter = 0;
    private int fieldCounter = 0;

    private final String basePackage;

    public NameRemapper(String basePackage, List<String> entryPoints) {
        this.basePackage = basePackage.endsWith("/") ? basePackage : basePackage + "/";
        for (String ep : entryPoints) {
            keepClasses.add(ep.replace('.', '/'));
        }
    }

    /**
     * Pass 1: Build the mapping dictionary.
     */
    public void analyze(Map<String, byte[]> classes) {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String className = entry.getKey().replace(".class", "");
            
            // Protect basePackage and our own runtime (io.ruguard)
            boolean isModClass = className.startsWith(basePackage);
            boolean isRuGuard = className.startsWith("io/ruguard/") && !className.startsWith("io/ruguard/annotation/");
            
            if ((!isModClass && !isRuGuard) || className.contains("mixin") || keepClasses.contains(className)) {
                keepClasses.add(className);
                continue;
            }

            // Keep NativeVM and RuguardBootstrap class names intact
            if (className.equals("io/ruguard/nativebridge/NativeVM") || className.equals("io/ruguard/bootstrap/RuguardBootstrap")) {
                keepClasses.add(className);
                continue;
            }

            ClassNode cn = new ClassNode();
            new ClassReader(entry.getValue()).accept(cn, ClassReader.SKIP_CODE);

            // Keep mixins
            boolean isMixin = false;
            if (cn.invisibleAnnotations != null && cn.invisibleAnnotations.stream().anyMatch(a -> a.desc.contains("Mixin"))) isMixin = true;
            if (cn.visibleAnnotations != null && cn.visibleAnnotations.stream().anyMatch(a -> a.desc.contains("Mixin"))) isMixin = true;
            
            if (isMixin) {
                keepClasses.add(className);
                continue;
            }

            String newClassName = generateClassName(classCounter++);
            mapping.put(className, newClassName);

            for (FieldNode fn : cn.fields) {
                String fieldKey = className + "." + fn.name + "." + fn.desc;
                mapping.put(fieldKey, generateName(fieldCounter++));
            }

            for (MethodNode mn : cn.methods) {
                if (keepMethods.contains(mn.name)) continue;
                
                // For io.ruguard classes, rename all methods except dispatch (JNI) and main
                if (isRuGuard) {
                    if (mn.name.equals("dispatch") || mn.name.equals("main")) continue;
                    mapping.put(className + "." + mn.name + "." + mn.desc, generateName(methodCounter++));
                    continue;
                }
                
                // For mod classes, safe to rename private or static methods
                boolean isPrivate = (mn.access & Opcodes.ACC_PRIVATE) != 0;
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
                
                if (isPrivate || isStatic) {
                    String methodKey = className + "." + mn.name + "." + mn.desc;
                    mapping.put(methodKey, generateName(methodCounter++));
                }
            }
        }
    }

    public String mapType(String internalName) {
        return mapping.getOrDefault(internalName, internalName);
    }

    /**
     * Pass 2: Apply the mapping.
     */
    public byte[] remap(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);
        
        Remapper remapper = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String key = owner + "." + name + "." + descriptor;
                return mapping.getOrDefault(key, name);
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                return name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String key = owner + "." + name + "." + descriptor;
                return mapping.getOrDefault(key, name);
            }

            @Override
            public String map(String internalName) {
                return mapping.getOrDefault(internalName, internalName);
            }
        };

        ClassRemapper classRemapper = new ClassRemapper(cw, remapper);
        cr.accept(classRemapper, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }
    
    public void dumpMappings(String path) {
        try {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                lines.add(e.getKey() + " -> " + e.getValue());
            }
            Files.write(Paths.get(path), lines);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String generateName(int index) {
        StringBuilder sb = new StringBuilder();
        int v = index;
        do {
            sb.append(chars.charAt(v % chars.length()));
            v /= chars.length();
        } while (v > 0);
        return sb.toString();
    }

    private String generateClassName(int index) {
        // Obfuscated package path e.g., a/b/C
        return "a/b/" + generateName(index);
    }
}
