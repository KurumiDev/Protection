package io.ruguard.cli;

import io.ruguard.annotation.Guarded;
import io.ruguard.annotation.RuguardProtected;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans a class for {@link Guarded} methods and returns enough metadata to
 * plan the transformation. Anything not annotated passes through untouched.
 *
 * <p>The scanner is read-only: it never modifies the class file, so it's
 * safe to run even on classes where the planner decides no transformation is
 * needed.
 */
public final class GuardedScanner {

    public static final class Plan {
        public final List<GuardedMethod> guarded = new ArrayList<>();
        public int access;
        public String name;
        public String superName;
        public String[] interfaces;
    }

    public static final class GuardedMethod {
        public final String name;
        public final String descriptor;
        public final int access;
        public final int index;
        public final Guarded.Level level;
        public GuardedMethod(String name, String descriptor, int access, int index, Guarded.Level level) {
            this.name = name; this.descriptor = descriptor; this.access = access;
            this.index = index; this.level = level;
        }
    }

    /** Inspects a class and returns a transform plan. */
    public static Plan plan(byte[] classBytes) {
        return plan(classBytes, null, null);
    }

    public static Plan plan(byte[] classBytes, String autoPrefix, Guarded.Level autoLevel) {
        Plan plan = new Plan();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new Scanner(plan, autoPrefix, autoLevel), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return plan;
    }

    private static final class Scanner extends ClassVisitor {
        private final Plan plan;
        private final String autoPrefix;
        private final Guarded.Level autoLevel;
        private int nextIndex = 0;

        Scanner(Plan plan, String autoPrefix, Guarded.Level autoLevel) {
            super(Opcodes.ASM9);
            this.plan = plan;
            this.autoPrefix = autoPrefix;
            this.autoLevel = autoLevel;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            plan.access = access; plan.name = name; plan.superName = superName;
            plan.interfaces = interfaces == null ? new String[0] : interfaces.clone();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.startsWith("<") || name.startsWith("$") || 
                (access & Opcodes.ACC_NATIVE) != 0 || 
                (access & Opcodes.ACC_ABSTRACT) != 0 ||
                (access & Opcodes.ACC_SYNTHETIC) != 0 ||
                (access & Opcodes.ACC_BRIDGE) != 0) {
                return null;
            }
            final int[] found = { -1 };
            final Guarded.Level[] lvl = { Guarded.Level.STANDARD };

            // Determine if the method should be auto-protected based on prefix (excluding mixins)
            boolean autoProtect = false;
            if (autoPrefix != null && plan.name != null && plan.name.startsWith(autoPrefix)) {
                if (!plan.name.contains("/mixins/")) {
                    autoProtect = true;
                    lvl[0] = autoLevel != null ? autoLevel : Guarded.Level.STRONG;
                }
            }
            final boolean isAuto = autoProtect;

            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if ("Lio/ruguard/annotation/Guarded;".equals(desc)) {
                        found[0] = 1;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitEnum(String n, String desc2, String value) {
                                if ("value".equals(n) && value != null) {
                                    try { lvl[0] = Guarded.Level.valueOf(value); } catch (Exception ignored) {}
                                }
                            }
                        };
                    }
                    return null;
                }
                @Override
                public void visitEnd() {
                    if (found[0] != -1 || isAuto) {
                        int idx = nextIndex++;
                        plan.guarded.add(new GuardedMethod(name, descriptor, access, idx, lvl[0]));
                    }
                }
            };
        }
    }
}
