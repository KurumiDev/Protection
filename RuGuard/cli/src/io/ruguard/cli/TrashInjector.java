package io.ruguard.cli;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Generates fake (trash) classes to confuse decompilers and human analysts.
 */
public class TrashInjector {

    private final Random rng;
    private final String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public TrashInjector(byte[] buildSeed) {
        long s = 0;
        for (int i = 0; i < buildSeed.length; i++) {
            s = s * 31 + (buildSeed[i] & 0xFF);
        }
        this.rng = new Random(s ^ 0xCAFEBEEFL);
    }

    /**
     * Generates a specified number of trash classes.
     * @param count number of classes to generate
     * @return map of internal class name to class bytes
     */
    public Map<String, byte[]> generate(int count) {
        Map<String, byte[]> trash = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String name = "a/b/" + randomString(4) + "$" + randomString(3);
            trash.put(name, generateTrashClass(name));
        }
        return trash;
    }

    private byte[] generateTrashClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, null, "java/lang/Object", null);

        // Trash fields
        int numFields = rng.nextInt(5) + 1;
        for (int i = 0; i < numFields; i++) {
            cw.visitField(Opcodes.ACC_PRIVATE, randomString(3), "Ljava/lang/String;", null, null).visitEnd();
        }

        // Default constructor
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // Trash methods with fake control flow
        int numMethods = rng.nextInt(3) + 1;
        for (int i = 0; i < numMethods; i++) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, randomString(4), "()V", null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitLdcInsn(randomString(10));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            
            // Add a jump to nowhere to confuse CFR
            org.objectweb.asm.Label l0 = new org.objectweb.asm.Label();
            mv.visitJumpInsn(Opcodes.GOTO, l0);
            mv.visitLabel(l0);
            
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
