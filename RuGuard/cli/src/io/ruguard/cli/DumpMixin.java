package io.ruguard.scratch;

import org.objectweb.asm.*;
import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DumpMixin {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("code/cataclysm/mixins/TextVisitFactoryMixin.class");
            if (entry == null) {
                System.out.println("TextVisitFactoryMixin not found!");
                return;
            }
            try (InputStream is = jar.getInputStream(entry)) {
                byte[] bytes = is.readAllBytes();
                ClassReader cr = new ClassReader(bytes);
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                        System.out.println("Class: " + name + " (super: " + superName + ")");
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        System.out.println("Method: " + name + " " + descriptor);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitLdcInsn(Object value) {
                                System.out.println("    LDC: " + value);
                            }
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                System.out.println("    Call: " + owner + "." + name + descriptor);
                            }
                            @Override
                            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                System.out.println("    Field: " + owner + "." + name + " " + descriptor);
                            }
                        };
                    }
                }, 0);
            }
        }
    }
}
