package io.ruguard.cli;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Encrypts all string constants (LDC instructions) in protected classes.
 * Each string is replaced with a call to an inline static decryptor method.
 *
 * Before: ldc "KillAura"
 * After:  ldc <encrypted_base64>
 *         ldc <key>
 *         invokestatic HostClass.$sd(Ljava/lang/String;I)Ljava/lang/String;
 */
public final class StringEncryptor {

    private final byte[] buildSeed;

    public StringEncryptor(byte[] buildSeed) {
        this.buildSeed = buildSeed;
    }

    /**
     * Encrypts all string constants in the given class bytes.
     * Returns transformed bytes, or null if no strings were found.
     */
    public byte[] encrypt(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        int classKey = classKeyFromName(cn.name);
        List<StringEntry> entries = new ArrayList<>();

        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.LDC && insn instanceof LdcInsnNode) {
                    LdcInsnNode ldc = (LdcInsnNode) insn;
                    if (ldc.cst instanceof String) {
                        String original = (String) ldc.cst;
                        if (original.isEmpty()) continue;
                        int stringKey = entries.size() * 31 + 17;
                        String encrypted = encryptString(original, classKey ^ stringKey);
                        entries.add(new StringEntry(mn, ldc, encrypted, stringKey));
                    }
                }
            }
        }

        if (entries.isEmpty()) return null;

        // Replace each LDC with call to $sd
        for (StringEntry e : entries) {
            InsnList patch = new InsnList();
            patch.add(new LdcInsnNode(e.encrypted));
            patch.add(new LdcInsnNode(e.stringKey));
            patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name,
                    "$sd", "(Ljava/lang/String;I)Ljava/lang/String;", false));
            e.method.instructions.insert(e.ldc, patch);
            e.method.instructions.remove(e.ldc);
        }

        // Inject $sd decryptor method
        injectDecryptor(cn, classKey);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private void injectDecryptor(ClassNode cn, int classKey) {
        // Check if $sd already exists
        for (MethodNode m : cn.methods) {
            if ("$sd".equals(m.name)) return;
        }

        MethodNode sd = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "$sd", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);

        sd.visitCode();
        // byte[] raw = Base64.getDecoder().decode(arg0);
        sd.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder",
                "()Ljava/util/Base64$Decoder;", false);
        sd.visitVarInsn(Opcodes.ALOAD, 0);
        sd.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode",
                "(Ljava/lang/String;)[B", false);
        sd.visitVarInsn(Opcodes.ASTORE, 2); // raw

        // int key = arg1 ^ classKey;
        sd.visitVarInsn(Opcodes.ILOAD, 1);
        sd.visitLdcInsn(classKey);
        sd.visitInsn(Opcodes.IXOR);
        sd.visitVarInsn(Opcodes.ISTORE, 3); // key

        // for (int i = 0; i < raw.length; i++) raw[i] ^= (key >>> (i % 4 * 8));
        sd.visitInsn(Opcodes.ICONST_0);
        sd.visitVarInsn(Opcodes.ISTORE, 4); // i = 0

        Label loopStart = new Label();
        Label loopEnd = new Label();

        sd.visitLabel(loopStart);
        sd.visitVarInsn(Opcodes.ILOAD, 4);
        sd.visitVarInsn(Opcodes.ALOAD, 2);
        sd.visitInsn(Opcodes.ARRAYLENGTH);
        sd.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);

        // raw[i] ^= (byte)(key >>> ((i % 4) * 8))
        sd.visitVarInsn(Opcodes.ALOAD, 2);
        sd.visitVarInsn(Opcodes.ILOAD, 4);
        sd.visitInsn(Opcodes.DUP2); // raw, i, raw, i
        sd.visitInsn(Opcodes.BALOAD); // raw, i, raw[i]

        sd.visitVarInsn(Opcodes.ILOAD, 3); // key
        sd.visitVarInsn(Opcodes.ILOAD, 4); // i
        sd.visitInsn(Opcodes.ICONST_4);
        sd.visitInsn(Opcodes.IREM);         // i % 4
        sd.visitIntInsn(Opcodes.BIPUSH, 8);
        sd.visitInsn(Opcodes.IMUL);         // (i%4)*8
        sd.visitInsn(Opcodes.IUSHR);        // key >>> ((i%4)*8)
        sd.visitInsn(Opcodes.I2B);          // (byte)

        sd.visitInsn(Opcodes.IXOR);
        sd.visitInsn(Opcodes.I2B);
        sd.visitInsn(Opcodes.BASTORE);

        sd.visitIincInsn(4, 1);
        sd.visitJumpInsn(Opcodes.GOTO, loopStart);

        sd.visitLabel(loopEnd);

        // return new String(raw, StandardCharsets.UTF_8);
        sd.visitTypeInsn(Opcodes.NEW, "java/lang/String");
        sd.visitInsn(Opcodes.DUP);
        sd.visitVarInsn(Opcodes.ALOAD, 2);
        sd.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8",
                "Ljava/nio/charset/Charset;");
        sd.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
                "([BLjava/nio/charset/Charset;)V", false);
        sd.visitInsn(Opcodes.ARETURN);

        sd.visitMaxs(0, 0);
        sd.visitEnd();

        cn.methods.add(sd);
    }

    private String encryptString(String original, int key) {
        byte[] utf8 = original.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < utf8.length; i++) {
            utf8[i] ^= (byte) (key >>> ((i % 4) * 8));
        }
        return java.util.Base64.getEncoder().encodeToString(utf8);
    }

    private int classKeyFromName(String className) {
        int h = 0x811c9dc5;
        for (byte b : className.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= 0x01000193;
        }
        // Mix in buildSeed
        for (int i = 0; i < buildSeed.length; i++) {
            h ^= (buildSeed[i] & 0xFF) << ((i % 4) * 8);
        }
        return h;
    }

    private static class StringEntry {
        final MethodNode method;
        final LdcInsnNode ldc;
        final String encrypted;
        final int stringKey;

        StringEntry(MethodNode method, LdcInsnNode ldc, String encrypted, int stringKey) {
            this.method = method;
            this.ldc = ldc;
            this.encrypted = encrypted;
            this.stringKey = stringKey;
        }
    }
}
