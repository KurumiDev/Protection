package io.ruguard.cli;

import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Control-Flow Flattener for RuGuard (Spec §6.3).
 *
 * Transforms the Control Flow Graph (CFG) of a MethodNode into a flat
 * state machine wrapped in a dispatch loop:
 * <pre>
 *   int state = entryState;
 *   while (state != exitState) {
 *       switch (state) {
 *           case S1: ... state = S2; break;
 *           case S2: ... state = S3; break;
 *       }
 *   }
 * </pre>
 *
 * State transitions are disguised using algebraic opaque predicates.
 */
public final class ControlFlowFlattener {

    private static final boolean DEBUG = false;

    private final byte[] buildSeed;
    private final Random rng;

    public ControlFlowFlattener(byte[] buildSeed) {
        this.buildSeed = buildSeed.clone();
        // Seed Random using the folded build seed for reproducibility
        long seed = 1125899906842597L;
        for (byte b : this.buildSeed) seed = 31 * seed + b;
        this.rng = new Random(seed);
    }

    /**
     * Flattens the control flow of the given method.
     *
     * @param mn the method node to flatten
     * @return true if the method was successfully flattened, false if skipped
     */
    public boolean flatten(MethodNode mn) {
        // Skip abstract/native methods, empty methods, or methods with try-catch/switches for stability
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (mn.instructions == null || mn.instructions.size() == 0) return false;
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return false;
        if (hasSwitches(mn)) return false;

        // 1. Identify basic blocks
        if (DEBUG) {
            System.out.println("DEBUG: Flattening " + mn.name + " " + mn.desc);
            printInstructions(mn.instructions, "Original " + mn.name);
        }

        List<Block> blocks = splitIntoBlocks(mn.instructions);
        if (blocks.size() < 2) {
            if (DEBUG) {
                System.out.println("DEBUG: Skipped " + mn.name + " (blocks.size() = " + blocks.size() + ")");
            }
            return false; // Not worth flattening
        }

        // 2. Assign unique, randomized state IDs to blocks
        Set<Integer> usedIds = new HashSet<>();
        Map<LabelNode, Integer> labelToState = new HashMap<>();

        for (Block b : blocks) {
            b.stateId = generateStateId(usedIds);
            labelToState.put(b.startLabel, b.stateId);
        }

        // 3. Find slot types by scanning store instructions to pre-initialize variables
        Map<Integer, Integer> slotToOpcode = new HashMap<>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode varInsn = (VarInsnNode) insn;
                int op = varInsn.getOpcode();
                if (op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE || op == Opcodes.DSTORE || op == Opcodes.ASTORE) {
                    slotToOpcode.put(varInsn.var, op);
                }
            }
        }

        // Determine parameter slots (which are already initialized by JVM)
        Type methodType = Type.getType(mn.desc);
        Type[] argTypes = methodType.getArgumentTypes();
        int paramSlots = ((mn.access & Opcodes.ACC_STATIC) != 0) ? 0 : 1; // 1 for 'this'
        for (Type t : argTypes) {
            paramSlots += t.getSize();
        }

        // Set up the state variable
        int stateVar = mn.maxLocals++;

        // 4. Build the loop header and LookupSwitch dispatcher
        InsnList newInstructions = new InsnList();
        LabelNode loopStart = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        // Initialize local variables to default values to avoid merge conflicts at loopStart
        for (Map.Entry<Integer, Integer> entry : slotToOpcode.entrySet()) {
            int slot = entry.getKey();
            if (slot >= paramSlots && slot != stateVar) {
                int op = entry.getValue();
                if (op == Opcodes.ISTORE) {
                    newInstructions.add(new InsnNode(Opcodes.ICONST_0));
                    newInstructions.add(new VarInsnNode(Opcodes.ISTORE, slot));
                } else if (op == Opcodes.LSTORE) {
                    newInstructions.add(new InsnNode(Opcodes.LCONST_0));
                    newInstructions.add(new VarInsnNode(Opcodes.LSTORE, slot));
                } else if (op == Opcodes.FSTORE) {
                    newInstructions.add(new InsnNode(Opcodes.FCONST_0));
                    newInstructions.add(new VarInsnNode(Opcodes.FSTORE, slot));
                } else if (op == Opcodes.DSTORE) {
                    newInstructions.add(new InsnNode(Opcodes.DCONST_0));
                    newInstructions.add(new VarInsnNode(Opcodes.DSTORE, slot));
                } else if (op == Opcodes.ASTORE) {
                    newInstructions.add(new InsnNode(Opcodes.ACONST_NULL));
                    newInstructions.add(new VarInsnNode(Opcodes.ASTORE, slot));
                }
            }
        }

        // state = entryState; goto loopStart;
        newInstructions.add(new LdcInsnNode(blocks.get(0).stateId));
        newInstructions.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        newInstructions.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        // loopStart: load state; switch(state) { ... }
        newInstructions.add(loopStart);
        newInstructions.add(new VarInsnNode(Opcodes.ILOAD, stateVar));

        // Sort keys and labels for LOOKUPSWITCH (required by bytecode spec)
        Map<Integer, LabelNode> sortedBlocks = new TreeMap<>();
        for (Block b : blocks) {
            sortedBlocks.put(b.stateId, b.startLabel);
        }

        int[] keys = new int[blocks.size()];
        LabelNode[] labels = new LabelNode[blocks.size()];
        int idx = 0;
        for (Map.Entry<Integer, LabelNode> entry : sortedBlocks.entrySet()) {
            keys[idx] = entry.getKey();
            labels[idx] = entry.getValue();
            idx++;
        }

        newInstructions.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));

        // 5. Rewrite state transitions inside each block
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            List<AbstractInsnNode> insns = b.instructions;
            AbstractInsnNode last = insns.isEmpty() ? null : insns.get(insns.size() - 1);

            // If the block is empty or last is null, it just falls through
            if (last == null) {
                emitOpaqueTransition(insns, getNextBlockState(blocks, i, labelToState), loopStart, usedIds, stateVar);
                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
                continue;
            }

            int opcode = last.getOpcode();

            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                // Return - exits method, keep as is
                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
            } else if (opcode == Opcodes.ATHROW) {
                // Throw - exits method, keep as is
                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
            } else if (opcode == Opcodes.GOTO) {
                // Unconditional branch
                JumpInsnNode gotoNode = (JumpInsnNode) last;
                insns.remove(insns.size() - 1);
                int targetState = labelToState.getOrDefault(gotoNode.label, 0);
                emitOpaqueTransition(insns, targetState, loopStart, usedIds, stateVar);
                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
            } else if (last instanceof JumpInsnNode) {
                // Conditional branch
                JumpInsnNode condNode = (JumpInsnNode) last;
                insns.remove(insns.size() - 1);

                int trueState = labelToState.getOrDefault(condNode.label, 0);
                int falseState = getNextBlockState(blocks, i, labelToState);

                LabelNode labelTrue = new LabelNode();

                // Rewrite condition to target labelTrue
                JumpInsnNode rewrittenCond = new JumpInsnNode(opcode, labelTrue);
                insns.add(rewrittenCond);

                // False path (fallthrough)
                insns.add(new LdcInsnNode(falseState));
                insns.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

                // True path
                insns.add(labelTrue);
                insns.add(new LdcInsnNode(trueState));
                insns.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
                insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
            } else {
                // Fallthrough to next block
                int targetState = getNextBlockState(blocks, i, labelToState);
                emitOpaqueTransition(insns, targetState, loopStart, usedIds, stateVar);
                for (AbstractInsnNode insn : insns) {
                    newInstructions.add(insn);
                }
            }
        }

        // 6. Default dispatcher block (should never be reached)
        newInstructions.add(defaultLabel);
        newInstructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        newInstructions.add(new InsnNode(Opcodes.DUP));
        newInstructions.add(new LdcInsnNode("RuGuard: Invalid state"));
        newInstructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false));
        newInstructions.add(new InsnNode(Opcodes.ATHROW));

        // 7. Update method instructions
        mn.instructions = newInstructions;
        if (DEBUG) {
            printInstructions(mn.instructions, "Flattened " + mn.name);
        }
        return true;
    }

    private void printInstructions(InsnList insns, String label) {
        System.out.println("=== Instructions: " + label + " ===");
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            String type = insn.getClass().getSimpleName();
            int op = insn.getOpcode();
            String opName = op >= 0 && op < 256 ? getOpcodeName(op) : "n/a";
            if (insn instanceof LabelNode) {
                System.out.println(i + ": LabelNode id=" + insn.hashCode());
            } else if (insn instanceof JumpInsnNode) {
                System.out.println(i + ": JumpInsnNode " + opName + " target=" + ((JumpInsnNode) insn).label.hashCode());
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode lswitch = (LookupSwitchInsnNode) insn;
                System.out.println(i + ": LookupSwitchInsnNode default=" + lswitch.dflt.hashCode());
                for (int j = 0; j < lswitch.keys.size(); j++) {
                    System.out.println("  key=" + lswitch.keys.get(j) + " -> " + lswitch.labels.get(j).hashCode());
                }
            } else {
                System.out.println(i + ": " + type + " " + opName);
            }
        }
        System.out.println("=================================");
    }

    private String getOpcodeName(int op) {
        // Return opcode name mapping or number
        switch (op) {
            case Opcodes.NOP: return "NOP";
            case Opcodes.ACONST_NULL: return "ACONST_NULL";
            case Opcodes.ICONST_M1: return "ICONST_M1";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.ICONST_2: return "ICONST_2";
            case Opcodes.ICONST_3: return "ICONST_3";
            case Opcodes.ICONST_4: return "ICONST_4";
            case Opcodes.ICONST_5: return "ICONST_5";
            case Opcodes.LCONST_0: return "LCONST_0";
            case Opcodes.LCONST_1: return "LCONST_1";
            case Opcodes.FCONST_0: return "FCONST_0";
            case Opcodes.FCONST_1: return "FCONST_1";
            case Opcodes.DCONST_0: return "DCONST_0";
            case Opcodes.DCONST_1: return "DCONST_1";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.SIPUSH: return "SIPUSH";
            case Opcodes.LDC: return "LDC";
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.LLOAD: return "LLOAD";
            case Opcodes.FLOAD: return "FLOAD";
            case Opcodes.DLOAD: return "DLOAD";
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ISTORE: return "ISTORE";
            case Opcodes.LSTORE: return "LSTORE";
            case Opcodes.FSTORE: return "FSTORE";
            case Opcodes.DSTORE: return "DSTORE";
            case Opcodes.ASTORE: return "ASTORE";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.LRETURN: return "LRETURN";
            case Opcodes.FRETURN: return "FRETURN";
            case Opcodes.DRETURN: return "DRETURN";
            case Opcodes.ARETURN: return "ARETURN";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.ATHROW: return "ATHROW";
            case Opcodes.GOTO: return "GOTO";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT";
            case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.IFLE: return "IFLE";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ";
            case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT";
            case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ: return "IF_ACMPEQ";
            case Opcodes.IF_ACMPNE: return "IF_ACMPNE";
            case Opcodes.IFNULL: return "IFNULL";
            case Opcodes.IFNONNULL: return "IFNONNULL";
            default: return String.valueOf(op);
        }
    }

    private static class Block {
        int stateId;
        LabelNode startLabel;
        final List<AbstractInsnNode> instructions = new ArrayList<>();
    }

    private List<Block> splitIntoBlocks(InsnList insns) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block();
        blocks.add(current);

        for (AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof LabelNode) {
                if (current.instructions.size() > 0) {
                    current = new Block();
                    blocks.add(current);
                }
                current.startLabel = (LabelNode) insn;
            }
            current.instructions.add(insn);
            if (isTerminator(insn)) {
                current = new Block();
                blocks.add(current);
            }
        }

        // Remove trailing empty block if any
        if (blocks.size() > 1 && blocks.get(blocks.size() - 1).instructions.size() == 0) {
            blocks.remove(blocks.size() - 1);
        }

        // Ensure every block has a start label
        for (Block b : blocks) {
            if (b.startLabel == null) {
                b.startLabel = new LabelNode();
                b.instructions.add(0, b.startLabel);
            }
        }

        return blocks;
    }

    private static boolean isTerminator(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
                || op == Opcodes.ATHROW
                || op == Opcodes.GOTO
                || (insn instanceof JumpInsnNode);
    }

    private static boolean hasSwitches(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                return true;
            }
        }
        return false;
    }

    private int generateStateId(Set<Integer> used) {
        while (true) {
            int id = rng.nextInt(900000) + 100000; // 6-digit state IDs
            if (used.add(id)) return id;
        }
    }

    private int getNextBlockState(List<Block> blocks, int currentIdx, Map<LabelNode, Integer> labelToState) {
        if (currentIdx + 1 < blocks.size()) {
            return blocks.get(currentIdx + 1).stateId;
        }
        return 0; // Terminal state ID
    }

    /**
     * Emits an opaque transition:
     * <pre>
     *   x = System.nanoTime() L2I;
     *   if ((x * x - x) % 2 == 0) {  // always true
     *       state = realState;
     *   } else {
     *       state = dummyState;
     *   }
     *   goto loopStart;
     * </pre>
     */
    private void emitOpaqueTransition(List<AbstractInsnNode> insns, int realState, LabelNode loopStart, Set<Integer> usedIds, int stateVar) {
        int dummyState = generateStateId(usedIds);
        LabelNode labelReal = new LabelNode();

        // Randomly choose one of the three equivalent formulas
        int formula = rng.nextInt(3);
        if (formula == 0) {
            // formula A: (x * x - x) % 2 == 0
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            insns.add(new InsnNode(Opcodes.L2I));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new InsnNode(Opcodes.SWAP));
            insns.add(new InsnNode(Opcodes.ISUB));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new InsnNode(Opcodes.IREM));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, labelReal));
        } else if (formula == 1) {
            // formula B: (x * x + x) % 2 == 0
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            insns.add(new InsnNode(Opcodes.L2I));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new InsnNode(Opcodes.SWAP));
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new InsnNode(Opcodes.IREM));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, labelReal));
        } else {
            // formula C: (x * (x + 1)) % 2 == 0
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            insns.add(new InsnNode(Opcodes.L2I));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new InsnNode(Opcodes.ICONST_1));
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new InsnNode(Opcodes.ICONST_2));
            insns.add(new InsnNode(Opcodes.IREM));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, labelReal));
        }

        // Dummy path (never executed)
        insns.add(new LdcInsnNode(dummyState));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        // Real path
        insns.add(labelReal);
        insns.add(new LdcInsnNode(realState));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stateVar));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
    }
}
