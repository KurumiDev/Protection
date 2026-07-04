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
 * Renames classes, methods, and fields to short, visually-ambiguous names
 * (mixes of {@code I}, {@code l}, {@code i}) so decompiler output is a wall of
 * indistinguishable identifiers.
 *
 * <p>Method renaming is override-safe: virtual (non-private, non-static)
 * methods are only renamed when the whole type hierarchy that could observe
 * them is inside our remap scope, so no override link to a JDK/library type is
 * ever broken. Overrides share a single generated name (resolved from the
 * top-most in-scope declarer) and inherited call sites are resolved by walking
 * the hierarchy at remap time.
 */
public class NameRemapper {

    private final Map<String, String> mapping = new HashMap<>();
    private final Set<String> keepClasses = new HashSet<>();
    private final Set<String> keepMethods = new HashSet<>(Arrays.asList(
            "<init>", "<clinit>", "main", "onInitialize", "onInitializeClient", "onInitializeServer",
            // java.lang.Object methods — never rename (external override link).
            "equals", "hashCode", "toString", "clone", "finalize", "getClass",
            "notify", "notifyAll", "wait"
    ));

    // Visually confusing identifier alphabet (all valid Java identifier starts).
    private final String chars = "Ili";
    private int classCounter = 0;
    private int methodCounter = 0;
    private int fieldCounter = 0;

    private final String basePackage;

    /** Full hierarchy view (name -> node) for every class we were handed. */
    private final Map<String, ClassNode> nodes = new HashMap<>();
    /** Classes eligible for internal renaming. */
    private final Set<String> inScope = new HashSet<>();
    /** Shared virtual-method names, keyed by "topDeclarer.name.desc". */
    private final Map<String, String> virtualNames = new HashMap<>();
    /** Virtual signatures ("name.desc") that must never be renamed because at
     *  least one in-scope declarer has an external hierarchy link. */
    private final Set<String> lockedVirtual = new HashSet<>();

    public NameRemapper(String basePackage, List<String> entryPoints) {
        this.basePackage = basePackage.endsWith("/") ? basePackage : basePackage + "/";
        // Do not add entry points to keepClasses to allow their renaming.
        // Their entry point methods (like onInitialize) are protected by keepMethods.
    }

    public Map<String, String> getMapping() {
        return mapping;
    }

    private static boolean hasMixinAnnotation(List<org.objectweb.asm.tree.AnnotationNode> visible, List<org.objectweb.asm.tree.AnnotationNode> invisible) {
        if (visible != null) {
            for (org.objectweb.asm.tree.AnnotationNode ann : visible) {
                if (ann.desc.startsWith("Lorg/spongepowered/asm/mixin/")) {
                    return true;
                }
            }
        }
        if (invisible != null) {
            for (org.objectweb.asm.tree.AnnotationNode ann : invisible) {
                if (ann.desc.startsWith("Lorg/spongepowered/asm/mixin/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pass 1: Build the mapping dictionary.
     */
    public void analyze(Map<String, byte[]> classes) {
        // 1a. Parse every class (metadata only) and classify scope.
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String className = entry.getKey().replace(".class", "");
            ClassNode cn = new ClassNode();
            new ClassReader(entry.getValue()).accept(cn, ClassReader.SKIP_CODE);
            nodes.put(className, cn);

            boolean isModClass = className.startsWith(basePackage);
            boolean isRuGuard = className.startsWith("io/ruguard/") && !className.startsWith("io/ruguard/annotation/");

            boolean isMixin = className.toLowerCase().contains("mixin");
            if (!isMixin) {
                if (cn.visibleAnnotations != null) {
                    for (org.objectweb.asm.tree.AnnotationNode ann : cn.visibleAnnotations) {
                        if (ann.desc != null && ann.desc.startsWith("Lorg/spongepowered/asm/mixin/")) {
                            isMixin = true;
                            break;
                        }
                    }
                }
                if (!isMixin && cn.invisibleAnnotations != null) {
                    for (org.objectweb.asm.tree.AnnotationNode ann : cn.invisibleAnnotations) {
                        if (ann.desc != null && ann.desc.startsWith("Lorg/spongepowered/asm/mixin/")) {
                            isMixin = true;
                            break;
                        }
                    }
                }
            }

            if (isMixin || (!isModClass && !isRuGuard) || keepClasses.contains(className)) {
                keepClasses.add(className);
                continue;
            }
            if (className.equals("io/ruguard/nativebridge/NativeVM") ||
                className.equals("io/ruguard/bootstrap/RuguardBootstrap") ||
                className.equals("io/ruguard/nativebridge/NativeEntropy") ||
                className.equals("io/ruguard/runtime/RuntimeContext") ||
                className.equals("io/ruguard/runtime/vm/VMInterpreter")) {
                keepClasses.add(className);
                continue;
            }
            inScope.add(className);
        }

        // 1a-lock. A virtual signature is renameable only if EVERY in-scope
        // class declaring it has a fully in-scope hierarchy. Otherwise lock it
        // globally so no override link to an external type is ever severed.
        for (String className : inScope) {
            ClassNode cn = nodes.get(className);
            boolean selfOk = allAncestorsInScope(className, new HashSet<>());
            for (MethodNode mn : cn.methods) {
                if (keepMethods.contains(mn.name)) continue;
                if (hasMixinAnnotation(mn.visibleAnnotations, mn.invisibleAnnotations)) continue;
                boolean isPrivate = (mn.access & Opcodes.ACC_PRIVATE) != 0;
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
                if (isPrivate || isStatic) continue;
                if (!selfOk) lockedVirtual.add(mn.name + "." + mn.desc);
            }
        }

        // 1b. Build the actual name mapping for in-scope classes.
        for (String className : inScope) {
            ClassNode cn = nodes.get(className);
            boolean isRuGuard = className.startsWith("io/ruguard/") && !className.startsWith("io/ruguard/annotation/");

            mapping.put(className, generateClassName(classCounter++));

            for (FieldNode fn : cn.fields) {
                if (hasMixinAnnotation(fn.visibleAnnotations, fn.invisibleAnnotations)) {
                    continue;
                }
                mapping.put(className + "." + fn.name + "." + fn.desc, generateName(fieldCounter++));
            }

            for (MethodNode mn : cn.methods) {
                if (keepMethods.contains(mn.name)) continue;
                if (hasMixinAnnotation(mn.visibleAnnotations, mn.invisibleAnnotations)) {
                    continue;
                }

                boolean isPrivate = (mn.access & Opcodes.ACC_PRIVATE) != 0;
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;

                if (isRuGuard) {
                    // Keep the JNI/main entry points; rename everything else.
                    if (mn.name.equals("dispatch") || mn.name.equals("main")) continue;
                    if (isPrivate || isStatic) {
                        mapping.put(className + "." + mn.name + "." + mn.desc, generateName(methodCounter++));
                    } else {
                        assignVirtual(className, mn.name, mn.desc);
                    }
                    continue;
                }

                // Mod classes.
                if (isPrivate || isStatic) {
                    mapping.put(className + "." + mn.name + "." + mn.desc, generateName(methodCounter++));
                } else {
                    // Virtual method — rename only if hierarchy is fully in scope.
                    assignVirtual(className, mn.name, mn.desc);
                }
            }
        }
    }

    /**
     * Assigns a shared obfuscated name to a virtual method, but only when the
     * entire type hierarchy of the owner is inside our remap scope (so we can't
     * break an override of a JDK/library method). Overrides resolve to the same
     * name via the top-most in-scope declarer.
     */
    private void assignVirtual(String owner, String name, String desc) {
        if (lockedVirtual.contains(name + "." + desc)) return;
        if (!allAncestorsInScope(owner, new HashSet<>())) return;
        String top = topDeclarer(owner, name, desc);
        String vkey = top + "." + name + "." + desc;
        String newName = virtualNames.get(vkey);
        if (newName == null) {
            newName = generateName(methodCounter++);
            virtualNames.put(vkey, newName);
        }
        mapping.put(owner + "." + name + "." + desc, newName);
    }

    /** True if super + all interfaces (recursively) are in scope, terminating
     *  only at java/lang/Object. Any unknown/external type fails the check. */
    private boolean allAncestorsInScope(String className, Set<String> seen) {
        if (!seen.add(className)) return true;
        ClassNode cn = nodes.get(className);
        if (cn == null) return false; // external type — unknown hierarchy
        String sup = cn.superName;
        if (sup != null && !sup.equals("java/lang/Object")) {
            if (!inScope.contains(sup) || !allAncestorsInScope(sup, seen)) return false;
        }
        if (cn.interfaces != null) {
            for (String itf : cn.interfaces) {
                if (!inScope.contains(itf) || !allAncestorsInScope(itf, seen)) return false;
            }
        }
        return true;
    }

    /** Finds the highest in-scope ancestor that declares name+desc (defaults to
     *  the owner itself). Guarantees overrides share one generated name. */
    private String topDeclarer(String owner, String name, String desc) {
        String result = owner;
        ClassNode cn = nodes.get(owner);
        if (cn != null && cn.superName != null && inScope.contains(cn.superName)) {
            if (declares(cn.superName, name, desc)) {
                result = topDeclarer(cn.superName, name, desc);
            }
        }
        if (cn != null && cn.interfaces != null) {
            for (String itf : cn.interfaces) {
                if (inScope.contains(itf) && declares(itf, name, desc)) {
                    return topDeclarer(itf, name, desc);
                }
            }
        }
        return result;
    }

    private boolean declares(String className, String name, String desc) {
        ClassNode cn = nodes.get(className);
        if (cn == null) return false;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasMixin(List<org.objectweb.asm.tree.AnnotationNode> anns) {
        return anns != null && anns.stream().anyMatch(a -> a.desc.contains("Mixin"));
    }

    /** Resolves a method/field name for a call site, following the hierarchy so
     *  inherited references (owner that doesn't itself declare the member) map
     *  to the renamed member on the declaring ancestor. */
    private String resolveMember(String owner, String name, String desc) {
        String direct = mapping.get(owner + "." + name + "." + desc);
        if (direct != null) return direct;
        ClassNode cn = nodes.get(owner);
        if (cn == null) return null;
        if (cn.superName != null) {
            String r = resolveMember(cn.superName, name, desc);
            if (r != null) return r;
        }
        if (cn.interfaces != null) {
            for (String itf : cn.interfaces) {
                String r = resolveMember(itf, name, desc);
                if (r != null) return r;
            }
        }
        return null;
    }

    public String mapType(String internalName) {
        return mapping.getOrDefault(internalName, internalName);
    }

    public Remapper createRemapper() {
        return new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String r = resolveMember(owner, name, descriptor);
                return r != null ? r : name;
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                return name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String r = resolveMember(owner, name, descriptor);
                return r != null ? r : name;
            }

            @Override
            public String map(String internalName) {
                return mapping.getOrDefault(internalName, internalName);
            }
        };
    }

    /**
     * Pass 2: Apply the mapping.
     */
    public byte[] remap(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);

        Remapper remapper = createRemapper();

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
        // Obfuscated package path e.g., a/b/IlI
        return "a/b/" + generateName(index);
    }
}
