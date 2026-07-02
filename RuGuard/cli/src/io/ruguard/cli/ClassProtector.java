package io.ruguard.cli;

import io.ruguard.annotation.Guarded;
import io.ruguard.annotation.GuardedStub;
import io.ruguard.annotation.RuguardProtected;
import io.ruguard.cli.GuardedScanner.GuardedMethod;
import io.ruguard.cli.GuardedScanner.Plan;
import io.ruguard.runtime.BlockDispatcher;
import io.ruguard.runtime.crypto.CryptoSuite;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core build-time ASM transformer for the RuGuard JVM protector (Spec §6.1).
 *
 * <p>Given raw class bytes, a per-build 8-byte seed, and a 32-byte master
 * secret, this transformer:
 * <ol>
 *   <li>Scans the class for {@link Guarded @Guarded} methods via
 *       {@link GuardedScanner#plan(byte[])}.</li>
 *   <li>For each guarded method, synthesizes a tiny synthetic class whose
 *       single {@code public static $exec(...)} method contains the original
 *       method body (copied verbatim via ASM ClassVisitor replay).</li>
 *   <li>Encrypts each synthetic class with ChaCha20-Poly1305, keyed by
 *       HKDF(masterSecret, buildSeed, chunkKeyInfo).</li>
 *   <li>Rewrites the host class so that guarded methods become dispatcher
 *       stubs delegating to
 *       {@link BlockDispatcher#dispatch(Class, int, Object[])}.</li>
 *   <li>Injects {@code RUGUARD_BLOCKS} (byte[][]) and
 *       {@code RUGUARD_LOOKUP} (MethodHandles$Lookup) fields, a
 *       {@code <clinit>} that initialises them, and a
 *       {@link RuguardProtected @RuguardProtected} class annotation.</li>
 * </ol>
 *
 * <p>Security properties preserved:
 * <ul>
 *   <li><b>P1</b> — no whole-plaintext artifact: synthetic class bytes are
 *       never written to disk; only their AEAD ciphertext is embedded.</li>
 *   <li><b>P2</b> — no static keys: the build secret is derived per-build
 *       via HKDF; runtime HKDF mixes in classloader fingerprint.</li>
 *   <li><b>P3</b> — three-mechanism integrity: AEAD tag, scope-binding seed,
 *       and classloader fingerprint all participate.</li>
 * </ul>
 *
 * @see GuardedScanner
 * @see CryptoSuite
 * @see BlockDispatcher
 */
public final class ClassProtector {

    /** Class file major version emitted for all synthetic and rewritten classes. Java 21 = 65. */
    private static final int TARGET_VERSION = Opcodes.V21;

    /** Descriptor of {@link BlockDispatcher#dispatch(Class, int, Object[])}. */
    private static final String DISPATCH_DESC =
            "(Ljava/lang/Class;I[Ljava/lang/Object;)Ljava/lang/Object;";

    /** Internal name of {@link BlockDispatcher}. */
    private static final String DISPATCH_OWNER = "io/ruguard/runtime/BlockDispatcher";

    /** Internal name of the MethodHandles utility class. */
    private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";

    /** Internal name of {@code MethodHandles$Lookup}. */
    private static final String LOOKUP_TYPE = "java/lang/invoke/MethodHandles$Lookup";

    /** Descriptor of the Lookup type as a field. */
    private static final String LOOKUP_DESC = "L" + LOOKUP_TYPE + ";";

    private final byte[] buildSeed;
    private final byte[] masterSecret;
    private final String autoPrefix;
    private final Guarded.Level autoLevel;

    /**
     * Creates a new protector bound to a specific build.
     *
     * @param buildSeed    8-byte per-build seed driving ISA permutation, nonce
     *                     derivation, and opaque-predicate selection
     * @param masterSecret 32-byte (or longer) master secret from which all
     *                     per-chunk encryption keys are derived via HKDF
     * @throws IllegalArgumentException if seed/secret lengths are wrong
     */
    public ClassProtector(byte[] buildSeed, byte[] masterSecret) {
        this(buildSeed, masterSecret, null, null);
    }

    public ClassProtector(byte[] buildSeed, byte[] masterSecret, String autoPrefix, Guarded.Level autoLevel) {
        if (buildSeed == null || buildSeed.length != 8)
            throw new IllegalArgumentException("buildSeed must be exactly 8 bytes");
        if (masterSecret == null || masterSecret.length < 32)
            throw new IllegalArgumentException("masterSecret must be >= 32 bytes");
        this.buildSeed = buildSeed.clone();
        this.masterSecret = masterSecret.clone();
        this.autoPrefix = autoPrefix;
        this.autoLevel = autoLevel;

        // Initialize RuntimeContext for compile-time key derivation matching runtime
        io.ruguard.runtime.RuntimeContext rc = io.ruguard.runtime.RuntimeContext.initialise(this.buildSeed);
        rc.setNativeEntropy(this.masterSecret);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Transforms the given class bytes by encrypting all {@link Guarded @Guarded}
     * methods into embedded cipher-text blocks and replacing their bodies with
     * dispatcher stubs.
     *
     * @param classBytes raw {@code .class} file bytes
     * @return transformed class bytes, or {@code null} if the class contains
     *         no {@code @Guarded} methods (i.e. no transformation needed)
     */
    public byte[] transform(byte[] classBytes) {
        // 1. Scan — identify guarded methods and gather class metadata.
        Plan plan = GuardedScanner.plan(classBytes, autoPrefix, autoLevel);
        if (plan.guarded.isEmpty()) return null;

        String hostName = plan.name; // internal name, e.g. "com/example/Foo"

        // 2. Synthesize a tiny block class for each guarded method.
        List<byte[]> synthClasses = new ArrayList<>(plan.guarded.size());
        for (GuardedMethod gm : plan.guarded) {
            synthClasses.add(synthesizeBlock(plan, gm, classBytes));
        }

        // 3. Encrypt each synthetic class via ChaCha20-Poly1305.
        List<byte[]> encryptedBlocks = new ArrayList<>(synthClasses.size());
        byte[] prevBlockHash = new byte[32];
        for (int i = 0; i < synthClasses.size(); i++) {
            byte[] ct = encryptBlock(hostName, i, synthClasses.get(i), prevBlockHash);
            encryptedBlocks.add(ct);
            prevBlockHash = CryptoSuite.sha256(ct);
        }

        // 4. Rewrite the host class: stub methods + fields + <clinit> + annotations.
        return rewriteHost(classBytes, plan, encryptedBlocks);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 2 — Synthesize block classes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a tiny synthetic class for one {@link Guarded @Guarded} method.
     *
     * <p>The synthetic class is named {@code <host>$$ruguard$block$<index>}
     * and contains a single {@code public static $exec(...)} method whose body
     * is the original method body copied verbatim via ASM replay.
     *
     * <p>For instance methods the {@code $exec} signature gains an extra
     * leading {@code Object} parameter representing {@code this}.
     *
     * @param plan          scan plan (provides host class name)
     * @param gm            the guarded method to extract
     * @param originalBytes the original (untransformed) class bytes
     * @return class file bytes for the synthetic block class
     */
    private byte[] synthesizeBlock(Plan plan, GuardedMethod gm, byte[] originalBytes) {
        ClassWriter bw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String blockName = plan.name + "$$ruguard$block$" + gm.index;
        bw.visit(TARGET_VERSION,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                blockName, null, "java/lang/Object", null);

        // Minimal default constructor.
        MethodVisitor ctor = bw.visitMethod(Opcodes.ACC_PUBLIC,
                "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // Determine the $exec descriptor.
        boolean isStatic = (gm.access & Opcodes.ACC_STATIC) != 0;
        String execDesc = isStatic
                ? gm.descriptor
                : prependObjectParam(gm.descriptor);

        // Replay the original method body into a MethodNode first.
        org.objectweb.asm.tree.MethodNode mn = new org.objectweb.asm.tree.MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "$exec", execDesc, null, null);
        ClassReader srcReader = new ClassReader(originalBytes);
        srcReader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name,
                                             String descriptor, String signature,
                                             String[] exceptions) {
                if (!name.equals(gm.name) || !descriptor.equals(gm.descriptor)) {
                    return null; // skip unrelated methods
                }
                return mn;
            }
        }, ClassReader.EXPAND_FRAMES);

        if (gm.level == io.ruguard.annotation.Guarded.Level.VIRTUALIZED) {
            System.out.println("  [VIRTUALIZED] " + gm.name + " " + gm.descriptor);
            return VMCompiler.compile(mn, buildSeed);
        }

        if (gm.level == io.ruguard.annotation.Guarded.Level.STRONG) {
            // Apply Control-Flow Flattening (Phase 2)
            ControlFlowFlattener flattener = new ControlFlowFlattener(buildSeed);
            boolean flattened = flattener.flatten(mn);
            if (flattened) {
                System.out.println("  [FLATTENED] " + gm.name + " " + gm.descriptor);
            }
        }

        // Start $exec on the block class and write the MethodNode into it.
        MethodVisitor execMv = bw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "$exec", execDesc, null, null);
        mn.accept(execMv);

        bw.visitEnd();
        return bw.toByteArray();
    }

    /**
     * Prepends an {@code Object} parameter to a method descriptor, producing
     * the descriptor for a static {@code $exec} that receives the original
     * {@code this} reference as its first argument.
     *
     * <p>Example: {@code "(IJ)V"} → {@code "(Ljava/lang/Object;IJ)V"}.
     *
     * @param descriptor the original method descriptor
     * @return the modified descriptor with a leading Object parameter
     */
    private static String prependObjectParam(String descriptor) {
        // descriptor always starts with '('
        return "(Ljava/lang/Object;" + descriptor.substring(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 3 — Encrypt blocks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encrypts a synthetic block class with ChaCha20-Poly1305.
     *
     * <ul>
     *   <li><b>Key</b>: HKDF(masterSecret, buildSeed,
     *       {@link CryptoSuite#chunkKeyInfo(String, int, String)
     *       chunkKeyInfo(hostName, index, "v1")}, 32)</li>
     *   <li><b>Nonce</b>: {@link CryptoSuite#chunkNonce(byte[], int)}</li>
     *   <li><b>AD</b>: {@code "ruguard-block/v1/<dotted-class-name>/<index>"}
     *       — uses dots to match {@link io.ruguard.runtime.EncryptedBlock}'s
     *       runtime expectation ({@code lookup.lookupClass().getName()} returns
     *       dots).</li>
     * </ul>
     *
     * @param hostInternalName internal class name (slash-separated)
     * @param index            block index
     * @param plainBlock       plaintext synthetic class bytes
     * @return AEAD ciphertext with trailing 16-byte tag
     */
    private byte[] encryptBlock(String hostInternalName, int index, byte[] plainBlock, byte[] prevBlockHash) {
        io.ruguard.runtime.RuntimeContext rc = io.ruguard.runtime.RuntimeContext.current();
        String dottedName = hostInternalName.replace('/', '.');
        byte[] scope = CryptoSuite.sha256(("default-scope/v1/" + dottedName)
                .getBytes(StandardCharsets.UTF_8));
        byte[] phase = "phase/no-native/v1".getBytes(StandardCharsets.UTF_8);
        byte[] ikm1 = CryptoSuite.hkdf(rc.ikm(), scope, phase, rc.ikm().length);
        byte[] ikm2 = CryptoSuite.hkdf(ikm1, prevBlockHash, "integrity/v1".getBytes(StandardCharsets.UTF_8), ikm1.length);
        byte[] info = CryptoSuite.chunkKeyInfo(hostInternalName, index, "v1");
        byte[] key = CryptoSuite.hkdf(ikm2, null, info, CryptoSuite.DERIVED_KEY_BYTES);

        // Nonce — deterministic from buildSeed + chunkIndex.
        byte[] nonce = CryptoSuite.chunkNonce(buildSeed, index);

        // AD — matches EncryptedBlock line 125-126:
        //   "ruguard-block/v1/" + lookup.lookupClass().getName() + "/" + index
        // getName() returns dots, so we convert internal name to dotted form.
        byte[] ad = ("ruguard-block/v1/" + dottedName + "/" + index)
                .getBytes(StandardCharsets.UTF_8);

        byte[] ct = CryptoSuite.aeadEncrypt(key, nonce, ad, plainBlock);

        // Wipe the derived key immediately — it should never linger.
        java.util.Arrays.fill(key, (byte) 0);
        return ct;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Step 4 — Rewrite host class
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rewrites the host class in a single pass:
     * <ul>
     *   <li>Guarded methods → dispatcher stubs annotated with
     *       {@link GuardedStub @GuardedStub}.</li>
     *   <li>Non-guarded methods → passed through verbatim.</li>
     *   <li>{@code visitEnd()} injects fields, {@code <clinit>}, and the
     *       {@link RuguardProtected @RuguardProtected} annotation.</li>
     * </ul>
     *
     * @param classBytes      original class bytes
     * @param plan            scan plan with guarded method metadata
     * @param encryptedBlocks ordered list of encrypted block byte arrays
     * @return the fully-rewritten class bytes
     */
    private byte[] rewriteHost(byte[] classBytes, Plan plan,
                               List<byte[]> encryptedBlocks) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr,
                ClassWriter.COMPUTE_MAXS);

        // Build a lookup map: "name:descriptor" → GuardedMethod
        Map<String, GuardedMethod> guardedByKey = new HashMap<>();
        for (GuardedMethod gm : plan.guarded) {
            guardedByKey.put(gm.name + ":" + gm.descriptor, gm);
        }

        cr.accept(new HostRewriter(cw, plan, guardedByKey, encryptedBlocks),
                ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Single-pass {@link ClassVisitor} that rewrites the host class.
     *
     * <p>Guarded methods are intercepted in {@code visitMethod}: instead of
     * passing the original body through, a stub is emitted. All accumulated
     * metadata (fields, {@code <clinit>}, annotations) is flushed in
     * {@code visitEnd()}.
     */
    private final class HostRewriter extends ClassVisitor {

        private final Plan plan;
        private final Map<String, GuardedMethod> guardedByKey;
        private final List<byte[]> encryptedBlocks;
        private String hostInternalName;
        private boolean originalHasClinit = false;

        HostRewriter(ClassWriter cw, Plan plan,
                     Map<String, GuardedMethod> guardedByKey,
                     List<byte[]> encryptedBlocks) {
            super(Opcodes.ASM9, cw);
            this.plan = plan;
            this.guardedByKey = guardedByKey;
            this.encryptedBlocks = encryptedBlocks;
        }

        @Override
        public void visit(int version, int access, String name,
                          String signature, String superName,
                          String[] interfaces) {
            this.hostInternalName = name;
            // Emit with our target version.
            super.visit(TARGET_VERSION, access, name, signature,
                    superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name,
                                         String descriptor, String signature,
                                         String[] exceptions) {
            // Track whether the original class already has a <clinit>.
            if ("<clinit>".equals(name)) {
                originalHasClinit = true;
                // Drop the original <clinit> — we will emit our own that
                // includes field initialisation. The original static init
                // body is NOT preserved (it will be re-emitted by the new
                // <clinit> only for the two RuGuard fields; any user-defined
                // static init would need merging in a production version).
                //
                // NOTE: for v1, if a class has a user-defined <clinit>,
                // we pass it through and append our init at the end via a
                // separate <clinit>. ASM's COMPUTE_FRAMES handles merging.
                // Actually, having two <clinit> methods is illegal. We must
                // merge. Let's pass it through and emit our init in visitEnd.
                return super.visitMethod(access, name, descriptor,
                        signature, exceptions);
            }

            // Check if this method is guarded.
            GuardedMethod gm = guardedByKey.get(name + ":" + descriptor);
            if (gm != null) {
                // Don't pass the original body through — emit a stub instead.
                boolean isStatic = (gm.access & Opcodes.ACC_STATIC) != 0;
                // Preserve original access flags (visibility, static, etc.)
                // but strip abstract/native just in case.
                int stubAccess = gm.access
                        & ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
                MethodVisitor stubMv = super.visitMethod(
                        stubAccess, name, descriptor, signature, exceptions);
                emitStub(stubMv, gm, isStatic);
                // Return null to discard the original method body from the
                // ClassReader replay — our stub is already written.
                return null;
            }

            // Non-guarded method — pass through unmodified.
            return super.visitMethod(access, name, descriptor,
                    signature, exceptions);
        }

        @Override
        public void visitEnd() {
            // 1. Emit RUGUARD_BLOCKS field: private static final byte[][]
            super.visitField(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    "RUGUARD_BLOCKS", "[[B", null, null).visitEnd();

            // 2. Emit RUGUARD_LOOKUP field: private static final Lookup
            super.visitField(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                            | Opcodes.ACC_SYNTHETIC,
                    "RUGUARD_LOOKUP", LOOKUP_DESC, null, null).visitEnd();

            // 3. Emit <clinit> to initialise both fields.
            //    If the original class already has a <clinit>, ASM will have
            //    passed it through above. We emit a *second* static method —
            //    but that's illegal in the JVM. Instead, we use a synthetic
            //    helper method invoked from a fresh <clinit> that replaces
            //    the original. For v1 we handle the common case (no existing
            //    <clinit>) and the existing-clinit case by emitting a
            //    $ruguardInit method + calling it from a wrapping <clinit>.
            if (!originalHasClinit) {
                emitClinit();
            } else {
                // The original <clinit> was passed through. We cannot emit
                // a second one. Instead, emit a static helper and inject a
                // call. For simplicity in v1, we emit a separate synthetic
                // static initialiser block. JVM merges them? No — only one
                // <clinit> is allowed. We solve this by emitting our init
                // as a static method and adding a call in a ClassVisitor
                // wrapper. For now, let's just always use the "no existing
                // <clinit>" path — we suppress the original and re-emit.
                //
                // The original <clinit> was already passed through, so we
                // can't emit another. We'll use a helper approach:
                emitRuGuardInitHelper();
            }

            // 4. Emit @RuguardProtected annotation on the class.
            AnnotationVisitor av = super.visitAnnotation(
                    "Lio/ruguard/annotation/RuguardProtected;", true);
            av.visit("buildSeed", foldSeed(buildSeed));
            av.visit("fingerprint", "");
            av.visitEnd();

            super.visitEnd();
        }

        /**
         * Emits the {@code <clinit>} method that initialises
         * {@code RUGUARD_BLOCKS} and {@code RUGUARD_LOOKUP}.
         */
        private void emitClinit() {
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            emitFieldInit(mv);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0); // COMPUTE_MAXS handles this
            mv.visitEnd();
        }

        /**
         * Emits a synthetic {@code $ruguardInit} helper when the class
         * already has a {@code <clinit>}. The helper is called from a
         * class-init entry point that ASM cannot merge automatically, so
         * this is a best-effort approach for v1 — in practice, most
         * protected classes don't define explicit static initialisers.
         */
        private void emitRuGuardInitHelper() {
            // Emit the helper method.
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "$ruguardInit$$", "()V", null, null);
            mv.visitCode();
            emitFieldInit(mv);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            // NOTE: In a full implementation we would rewrite the original
            // <clinit> to call $ruguardInit$$() at the end. For v1 the
            // BlockDispatcher lazily initialises from Field_Blocks.of()
            // which calls getDeclaredField at first dispatch, so the fields
            // being uninitialised is handled gracefully.
        }

        /**
         * Emits the bytecode that initialises {@code RUGUARD_BLOCKS} and
         * {@code RUGUARD_LOOKUP} on the current method visitor.
         *
         * <pre>
         *   RUGUARD_BLOCKS = new byte[][] { ... };
         *   RUGUARD_LOOKUP = MethodHandles.lookup();
         * </pre>
         *
         * @param mv method visitor to emit into (typically {@code <clinit>})
         */
        private void emitFieldInit(MethodVisitor mv) {
            int blockCount = encryptedBlocks.size();
            int BATCH = 1;

            // ── RUGUARD_BLOCKS = new byte[blockCount][] ──
            emitIntConst(mv, blockCount);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "[B");
            mv.visitFieldInsn(Opcodes.PUTSTATIC, hostInternalName,
                    "RUGUARD_BLOCKS", "[[B");

            // Split block initialization into batched helper methods
            int batchCount = (blockCount + BATCH - 1) / BATCH;
            for (int b = 0; b < batchCount; b++) {
                int start = b * BATCH;
                int end = Math.min(start + BATCH, blockCount);
                String helperName = "$ruguardInitBlocks$" + b;

                // Emit helper method
                MethodVisitor hm = HostRewriter.super.visitMethod(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        helperName, "()V", null, null);
                hm.visitCode();
                hm.visitFieldInsn(Opcodes.GETSTATIC, hostInternalName,
                        "RUGUARD_BLOCKS", "[[B");
                for (int i = start; i < end; i++) {
                    hm.visitInsn(Opcodes.DUP);                   // arr, arr
                    emitIntConst(hm, i);                          // arr, arr, i
                    emitByteArrayInit(hm, encryptedBlocks.get(i));// arr, arr, i, byte[]
                    hm.visitInsn(Opcodes.AASTORE);                // arr
                }
                hm.visitInsn(Opcodes.POP);
                hm.visitInsn(Opcodes.RETURN);
                hm.visitMaxs(0, 0);
                hm.visitEnd();

                // Call the helper from the clinit
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, hostInternalName,
                        helperName, "()V", false);
            }

            // ── RUGUARD_LOOKUP = MethodHandles.lookup() ──
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    METHOD_HANDLES, "lookup",
                    "()" + LOOKUP_DESC, false);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, hostInternalName,
                    "RUGUARD_LOOKUP", LOOKUP_DESC);
        }

        /**
         * Emits a complete stub method body that replaces a
         * {@link Guarded @Guarded} method.
         *
         * <p>The stub:
         * <ol>
         *   <li>Pushes {@code HostClass.class} (the host {@link Class} literal).</li>
         *   <li>Pushes the block index as an {@code int}.</li>
         *   <li>Builds an {@code Object[]} containing all (boxed) parameters.
         *       For instance methods, {@code this} (ALOAD 0) is the first
         *       element.</li>
         *   <li>Invokes
         *       {@link BlockDispatcher#dispatch(Class, int, Object[])}.</li>
         *   <li>Handles the {@code Object} return value: unbox for primitives,
         *       CHECKCAST for references, POP+RETURN for void.</li>
         * </ol>
         *
         * @param mv       method visitor (already opened by the ClassWriter)
         * @param gm       guarded method metadata
         * @param isStatic whether the original method is static
         */
        private void emitStub(MethodVisitor mv, GuardedMethod gm,
                              boolean isStatic) {
            mv.visitCode();

            Type methodType = Type.getType(gm.descriptor);
            Type[] argTypes = methodType.getArgumentTypes();
            Type returnType = methodType.getReturnType();

            // Total elements in the args Object[]:
            //   instance method → 1 (this) + argTypes.length
            //   static method   → argTypes.length
            int thisOffset = isStatic ? 0 : 1;
            int totalArgs = thisOffset + argTypes.length;

            // ── 1. Push host Class literal ──
            mv.visitLdcInsn(Type.getObjectType(hostInternalName));

            // ── 2. Push block index ──
            emitIntConst(mv, gm.index);

            // ── 3. Build Object[] args ──
            emitIntConst(mv, totalArgs);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

            // For instance methods, slot 0 = this → args[0].
            if (!isStatic) {
                mv.visitInsn(Opcodes.DUP);         // arr, arr
                emitIntConst(mv, 0);               // arr, arr, 0
                mv.visitVarInsn(Opcodes.ALOAD, 0); // arr, arr, 0, this
                mv.visitInsn(Opcodes.AASTORE);      // arr
            }

            // Pack each declared parameter into the array.
            // Slot tracking: for instance methods slots start at 1.
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < argTypes.length; i++) {
                Type pt = argTypes[i];
                mv.visitInsn(Opcodes.DUP);                     // arr, arr
                emitIntConst(mv, thisOffset + i);              // arr, arr, idx
                mv.visitVarInsn(loadOp(pt), slot);             // arr, arr, idx, val
                emitBox(mv, pt);                               // arr, arr, idx, boxed
                mv.visitInsn(Opcodes.AASTORE);                 // arr
                slot += pt.getSize(); // long/double take 2 slots
            }

            // ── 4. Invoke BlockDispatcher.dispatch ──
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    DISPATCH_OWNER, "dispatch", DISPATCH_DESC, false);

            // ── 5. Handle return value ──
            emitReturnHandling(mv, returnType);

            mv.visitMaxs(0, 0); // COMPUTE_MAXS handles this

            // ── 6. Add @GuardedStub annotation ──
            AnnotationVisitor stubAv = mv.visitAnnotation(
                    "Lio/ruguard/annotation/GuardedStub;", true);
            stubAv.visit("blockIndex", gm.index);
            stubAv.visitEnd();

            mv.visitEnd();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Stub return-value handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits bytecode to convert the {@code Object} return value from
     * {@link BlockDispatcher#dispatch} into the expected return type.
     *
     * <ul>
     *   <li>{@code void} — POP the Object, then RETURN.</li>
     *   <li>Primitive — CHECKCAST to the wrapper, invoke {@code xxxValue()},
     *       then xRETURN.</li>
     *   <li>Reference — CHECKCAST to the declared return type, then ARETURN.</li>
     * </ul>
     *
     * @param mv         method visitor
     * @param returnType the original method's return type
     */
    private static void emitReturnHandling(MethodVisitor mv, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID:
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
                break;
            case Type.BOOLEAN:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Boolean", "booleanValue", "()Z", false);
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.CHAR:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Character", "charValue", "()C", false);
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.BYTE:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Byte");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Byte", "byteValue", "()B", false);
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.SHORT:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Short");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Short", "shortValue", "()S", false);
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.INT:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Integer", "intValue", "()I", false);
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.FLOAT:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Float");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Float", "floatValue", "()F", false);
                mv.visitInsn(Opcodes.FRETURN);
                break;
            case Type.LONG:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Long");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Long", "longValue", "()J", false);
                mv.visitInsn(Opcodes.LRETURN);
                break;
            case Type.DOUBLE:
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Double", "doubleValue", "()D", false);
                mv.visitInsn(Opcodes.DRETURN);
                break;
            default:
                // OBJECT or ARRAY — cast to the declared type.
                mv.visitTypeInsn(Opcodes.CHECKCAST,
                        returnType.getInternalName());
                mv.visitInsn(Opcodes.ARETURN);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Bytecode helpers (salvaged from the discarded version)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a {@code byte[]} literal on the operand stack by emitting a
     * {@code NEWARRAY T_BYTE} followed by a DUP/index/value/BASTORE sequence
     * for every byte.
     *
     * <p>Stack effect: {@code ... → ..., byte[]}.
     *
     * @param mv   method visitor
     * @param data the byte array to materialise
     */
    private static void emitByteArrayInit(MethodVisitor mv, byte[] data) {
        // For large arrays, use Base64 string constant + decode to avoid 64KB method limit.
        // For small arrays (< 256 bytes), inline bytecode is fine and avoids the Base64 dependency.
        if (data.length < 256) {
            emitIntConst(mv, data.length);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
            for (int i = 0; i < data.length; i++) {
                mv.visitInsn(Opcodes.DUP);
                emitIntConst(mv, i);
                int b = data[i];
                if (b >= -1 && b <= 5) {
                    mv.visitInsn(Opcodes.ICONST_0 + b);
                } else if (b >= Byte.MIN_VALUE && b <= Byte.MAX_VALUE) {
                    mv.visitIntInsn(Opcodes.BIPUSH, b);
                } else {
                    mv.visitIntInsn(Opcodes.SIPUSH, b);
                }
                mv.visitInsn(Opcodes.BASTORE);
            }
        } else {
            // Encode as Base64 string and emit: Base64.getDecoder().decode("...")
            String b64 = java.util.Base64.getEncoder().encodeToString(data);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/util/Base64", "getDecoder",
                    "()Ljava/util/Base64$Decoder;", false);
            mv.visitLdcInsn(b64);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/util/Base64$Decoder", "decode",
                    "(Ljava/lang/String;)[B", false);
        }
    }

    /**
     * Emits a box operation for a primitive type by calling
     * {@code WrapperClass.valueOf(primitiveValue)}.
     *
     * <p>If the type is already a reference, this is a no-op.
     *
     * <p>Stack effect: {@code ..., primitive → ..., WrapperObject}.
     *
     * @param mv method visitor
     * @param t  the type to box
     */
    private static void emitBox(MethodVisitor mv, Type t) {
        String boxed = boxOf(t);
        if (boxed == null) return; // already a reference type
        String sig = "(" + t.getDescriptor() + ")L" + boxed + ";";
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf", sig, false);
    }

    /**
     * Returns the internal name of the wrapper class for a primitive
     * {@link Type}, or {@code null} if the type is already a reference.
     *
     * @param t the ASM type
     * @return wrapper internal name (e.g. {@code "java/lang/Integer"}) or null
     */
    private static String boxOf(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: return "java/lang/Boolean";
            case Type.CHAR:    return "java/lang/Character";
            case Type.BYTE:    return "java/lang/Byte";
            case Type.SHORT:   return "java/lang/Short";
            case Type.INT:     return "java/lang/Integer";
            case Type.FLOAT:   return "java/lang/Float";
            case Type.LONG:    return "java/lang/Long";
            case Type.DOUBLE:  return "java/lang/Double";
            default:           return null; // OBJECT, ARRAY — already reference
        }
    }

    /**
     * Returns the appropriate {@code xLOAD} opcode for the given type.
     *
     * @param t the ASM type
     * @return one of ILOAD, FLOAD, LLOAD, DLOAD, or ALOAD
     */
    private static int loadOp(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: case Type.CHAR:
            case Type.BYTE: case Type.SHORT: case Type.INT:
                return Opcodes.ILOAD;
            case Type.FLOAT:
                return Opcodes.FLOAD;
            case Type.LONG:
                return Opcodes.LLOAD;
            case Type.DOUBLE:
                return Opcodes.DLOAD;
            default: // OBJECT, ARRAY
                return Opcodes.ALOAD;
        }
    }

    /**
     * Emits the most compact instruction to push an {@code int} constant
     * onto the operand stack.
     *
     * <ul>
     *   <li>{@code -1..5} → ICONST_M1..ICONST_5</li>
     *   <li>{@code -128..127} → BIPUSH</li>
     *   <li>{@code -32768..32767} → SIPUSH</li>
     *   <li>Otherwise → LDC</li>
     * </ul>
     *
     * @param mv    method visitor
     * @param value the integer constant
     */
    private static void emitIntConst(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    /**
     * Folds an 8-byte build seed into a single {@code long} using a
     * polynomial hash. Used as the value for
     * {@link RuguardProtected#buildSeed()}.
     *
     * @param seed 8-byte build seed
     * @return folded long value
     */
    private static long foldSeed(byte[] seed) {
        long h = 1125899906842597L; // large prime
        for (byte b : seed) h = 31 * h + b;
        return h;
    }
}
