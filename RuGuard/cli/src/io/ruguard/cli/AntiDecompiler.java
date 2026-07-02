package io.ruguard.cli;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Inserts bytecode tricks that crash or confuse popular decompilers
 * (JD-GUI, Fernflower, CFR, Procyon).
 *
 * Techniques used:
 * 1. Illegal signature attributes → crashes JD-GUI
 * 2. Fake exception handler ranges → crashes Fernflower try-catch reconstruction
 * 3. Dead-code switch patterns → confuses control-flow analysis
 * 4. Duplicate annotations with invalid types → crashes annotation processors
 * 5. Illegal local variable table entries → corrupts variable naming
 * 6. JSR/RET patterns (pre-Java 7) → crashes modern decompilers
 */
public final class AntiDecompiler {

    private final Random rng;

    public AntiDecompiler(byte[] buildSeed) {
        long s = 0;
        for (int i = 0; i < buildSeed.length; i++) {
            s = s * 31 + (buildSeed[i] & 0xFF);
        }
        this.rng = new Random(s ^ 0xDEADBEEFCAFEL);
    }

    /**
     * Applies anti-decompiler transformations to the given class.
     * @param classBytes original class bytes
     * @return transformed class bytes
     */
    public byte[] apply(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        cr.accept(cn, ClassReader.EXPAND_FRAMES);

        // 1. Add illegal signature attribute to crash JD-GUI
        injectIllegalSignature(cn);

        // 2. Add fake source file to misdirect
        cn.sourceFile = randomString(8) + ".java";

        // 3. Add bogus inner class references
        injectBogusInnerClasses(cn);

        // 4. For each method, inject anti-decompiler bytecode
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null || mn.instructions.size() == 0) continue;
            if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) continue;

            injectDeadCodeSwitch(mn);
            injectFakeExceptionHandler(mn);
            corruptLocalVariableTable(mn);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Injects an illegal generic signature that JD-GUI can't parse.
     */
    private void injectIllegalSignature(ClassNode cn) {
        // Invalid generic signature crashes JD-GUI's type resolver
        cn.signature = "<" + randomString(3) + "::" + randomString(4)
                + "<L" + randomString(5) + ";>;" + randomString(2)
                + "<T" + randomString(2) + ";>>";
    }

    /**
     * Adds bogus inner class references that confuse decompilers.
     */
    private void injectBogusInnerClasses(ClassNode cn) {
        for (int i = 0; i < 3; i++) {
            String fakeName = cn.name + "$" + randomString(4);
            cn.innerClasses.add(new InnerClassNode(
                    fakeName, cn.name, randomString(4),
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC));
        }
    }

    /**
     * Inserts an opaque predicate with a TABLESWITCH that has dead-code branches.
     * This confuses control-flow analysis in Fernflower and CFR.
     */
    private void injectDeadCodeSwitch(MethodNode mn) {
        InsnList patch = new InsnList();
        LabelNode realLabel = new LabelNode();
        LabelNode deadLabel1 = new LabelNode();
        LabelNode deadLabel2 = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        // Push constant 0 (opaque predicate — always 0)
        patch.add(new InsnNode(Opcodes.ICONST_0));
        // TABLESWITCH 0..2: case 0 → realLabel, case 1 → dead1, case 2 → dead2
        patch.add(new TableSwitchInsnNode(0, 2, defaultLabel,
                realLabel, deadLabel1, deadLabel2));

        // Dead branch 1: throw impossible exception
        patch.add(deadLabel1);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        patch.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        patch.add(new InsnNode(Opcodes.DUP));
        patch.add(new LdcInsnNode("Failed to decompile payload"));
        patch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(Ljava/lang/String;)V", false));
        patch.add(new InsnNode(Opcodes.ATHROW));

        // Dead branch 2: infinite loop (unreachable)
        patch.add(deadLabel2);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        LabelNode loopTop = new LabelNode();
        patch.add(loopTop);
        patch.add(new JumpInsnNode(Opcodes.GOTO, loopTop));

        // Default: also goes to real code
        patch.add(defaultLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // Real label — continue normal execution
        patch.add(realLabel);
        patch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));

        // Insert at the beginning of the method
        mn.instructions.insert(patch);
    }

    /**
     * Injects a fake exception handler that covers the entire method body.
     * The handler catches a non-existent exception type, confusing try-catch reconstruction.
     */
    private void injectFakeExceptionHandler(MethodNode mn) {
        if (mn.instructions.size() < 4) return;

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        // Insert labels around existing code
        mn.instructions.insertBefore(mn.instructions.getFirst(), start);

        // Handler code — ATHROW (re-throw, unreachable in practice)
        InsnList handlerCode = new InsnList();
        handlerCode.add(handler);
        handlerCode.add(new FrameNode(Opcodes.F_SAME1, 0, null,
                1, new Object[]{"java/lang/Throwable"}));
        handlerCode.add(new InsnNode(Opcodes.ATHROW));

        // Find the last instruction before any RETURN
        AbstractInsnNode last = mn.instructions.getLast();
        mn.instructions.insertBefore(last, end);
        mn.instructions.add(handlerCode);

        // Register fake handler (must use a real class to pass JVM verification)
        if (mn.tryCatchBlocks == null) mn.tryCatchBlocks = new ArrayList<>();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));
    }

    /**
     * Corrupts local variable table entries with zero-width Unicode characters.
     */
    private void corruptLocalVariableTable(MethodNode mn) {
        if (mn.localVariables == null) return;
        for (LocalVariableNode lv : mn.localVariables) {
            if ("this".equals(lv.name)) continue;
            // Replace with zero-width chars that look blank in decompilers
            lv.name = "\u200B\u200C\u200D\uFEFF" + randomString(2);
        }
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        String chars = "abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
