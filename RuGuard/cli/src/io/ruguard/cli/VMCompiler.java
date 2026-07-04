package io.ruguard.cli;

import io.ruguard.runtime.vm.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;

/**
 * Compiles a JVM MethodNode into VMBlock custom stack-based bytecode.
 */
public final class VMCompiler {

    private VMCompiler() {}

    /**
     * Translates a MethodNode's bytecode to the VM block payload format.
     */
    public static byte[] compile(MethodNode mn, byte[] buildSeed, NameRemapper nameRemapper) {
        int[] opcodeMap = VMInstructionShuffler.getOpcodeMap(buildSeed);

        org.objectweb.asm.commons.Remapper remapper = nameRemapper == null ? null : nameRemapper.createRemapper();

        List<Object> constantPool = new ArrayList<>();
        Map<AbstractInsnNode, Integer> positions = new HashMap<>();
        Map<LabelNode, Integer> labelPositions = new HashMap<>();

        // First Pass: Assign instruction positions and record label positions
        int currentSize = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                labelPositions.put((LabelNode) insn, currentSize);
            }
            positions.put(insn, currentSize);
            currentSize += getInstructionSize(insn);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        // Second Pass: Emit bytecode and compile constants
        try {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
                    continue;
                }

                int op = insn.getOpcode();
                int stdOp = mapToStdOpcode(insn);
                int shuffledOp = opcodeMap[stdOp];
                dos.writeByte(shuffledOp);

                switch (stdOp) {
                    case VMInterpreter.VM_NOP:
                    case VMInterpreter.VM_ACONST_NULL:
                    case VMInterpreter.VM_IADD:
                    case VMInterpreter.VM_ISUB:
                    case VMInterpreter.VM_IMUL:
                    case VMInterpreter.VM_IDIV:
                    case VMInterpreter.VM_LADD:
                    case VMInterpreter.VM_LSUB:
                    case VMInterpreter.VM_LMUL:
                    case VMInterpreter.VM_LDIV:
                    case VMInterpreter.VM_I2L:
                    case VMInterpreter.VM_IRETURN:
                    case VMInterpreter.VM_LRETURN:
                    case VMInterpreter.VM_ARETURN:
                    case VMInterpreter.VM_RETURN:
                        break;

                    case VMInterpreter.VM_ICONST: {
                        int val = getConstIntValue(insn);
                        dos.writeInt(val);
                        break;
                    }

                    case VMInterpreter.VM_LCONST: {
                        long val = getConstLongValue(insn);
                        dos.writeLong(val);
                        break;
                    }

                    case VMInterpreter.VM_FCONST: {
                        float val = getConstFloatValue(insn);
                        dos.writeInt(Float.floatToIntBits(val));
                        break;
                    }

                    case VMInterpreter.VM_DCONST: {
                        double val = getConstDoubleValue(insn);
                        dos.writeLong(Double.doubleToLongBits(val));
                        break;
                    }

                    case VMInterpreter.VM_ILOAD:
                    case VMInterpreter.VM_LLOAD:
                    case VMInterpreter.VM_FLOAD:
                    case VMInterpreter.VM_DLOAD:
                    case VMInterpreter.VM_ALOAD:
                    case VMInterpreter.VM_ISTORE:
                    case VMInterpreter.VM_LSTORE:
                    case VMInterpreter.VM_FSTORE:
                    case VMInterpreter.VM_DSTORE:
                    case VMInterpreter.VM_ASTORE: {
                        int idx = ((VarInsnNode) insn).var;
                        dos.writeByte(idx);
                        break;
                    }

                    case VMInterpreter.VM_IINC: {
                        IincInsnNode iinc = (IincInsnNode) insn;
                        dos.writeByte(iinc.var);
                        dos.writeByte(iinc.incr);
                        break;
                    }

                    case VMInterpreter.VM_GOTO:
                    case VMInterpreter.VM_IFEQ:
                    case VMInterpreter.VM_IFNE:
                    case VMInterpreter.VM_IFLT:
                    case VMInterpreter.VM_IFGE:
                    case VMInterpreter.VM_IFGT:
                    case VMInterpreter.VM_IFLE:
                    case VMInterpreter.VM_IF_ICMPEQ:
                    case VMInterpreter.VM_IF_ICMPNE:
                    case VMInterpreter.VM_IF_ICMPLT:
                    case VMInterpreter.VM_IF_ICMPGE:
                    case VMInterpreter.VM_IF_ICMPGT:
                    case VMInterpreter.VM_IF_ICMPLE:
                    case VMInterpreter.VM_IF_ACMPEQ:
                    case VMInterpreter.VM_IF_ACMPNE:
                    case VMInterpreter.VM_IFNULL:
                    case VMInterpreter.VM_IFNONNULL: {
                        JumpInsnNode jump = (JumpInsnNode) insn;
                        int targetPos = labelPositions.get(jump.label);
                        int currentPos = positions.get(insn);
                        int offset = targetPos - currentPos;
                        dos.writeShort(offset);
                        break;
                    }

                    case VMInterpreter.VM_LDC: {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        Object val = ldc.cst;
                        if (val instanceof Type) {
                            val = ((Type) val).getDescriptor();
                        }
                        if (val instanceof String && remapper != null) {
                            if (((String) val).startsWith("L") && ((String) val).endsWith(";")) {
                                val = remapper.mapDesc((String) val);
                            } else if (((String) val).contains("/")) {
                                val = remapper.mapType((String) val);
                            }
                        }
                        int idx = addConstant(constantPool, val);
                        dos.writeShort(idx);
                        break;
                    }

                    case VMInterpreter.VM_INVOKESTATIC:
                    case VMInterpreter.VM_INVOKEVIRTUAL:
                    case VMInterpreter.VM_INVOKESPECIAL:
                    case VMInterpreter.VM_INVOKEINTERFACE: {
                        MethodInsnNode minsn = (MethodInsnNode) insn;
                        String owner = remapper != null ? remapper.mapType(minsn.owner) : minsn.owner;
                        String name = remapper != null ? remapper.mapMethodName(minsn.owner, minsn.name, minsn.desc) : minsn.name;
                        String desc = remapper != null ? remapper.mapMethodDesc(minsn.desc) : minsn.desc;
                        VMMethodRef ref = new VMMethodRef(owner, name, desc, minsn.itf ? 1 : 0);
                        int idx = addConstant(constantPool, ref);
                        dos.writeShort(idx);
                        break;
                    }

                    case VMInterpreter.VM_GETSTATIC:
                    case VMInterpreter.VM_PUTSTATIC:
                    case VMInterpreter.VM_GETFIELD:
                    case VMInterpreter.VM_PUTFIELD: {
                        FieldInsnNode finsn = (FieldInsnNode) insn;
                        String owner = remapper != null ? remapper.mapType(finsn.owner) : finsn.owner;
                        String name = remapper != null ? remapper.mapFieldName(finsn.owner, finsn.name, finsn.desc) : finsn.name;
                        String desc = remapper != null ? remapper.mapDesc(finsn.desc) : finsn.desc;
                        VMFieldRef ref = new VMFieldRef(owner, name, desc);
                        int idx = addConstant(constantPool, ref);
                        dos.writeShort(idx);
                        break;
                    }

                    case VMInterpreter.VM_INVOKEDYNAMIC: {
                        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                        String bsmOwner = remapper != null ? remapper.mapType(indy.bsm.getOwner()) : indy.bsm.getOwner();
                        String bsmName = remapper != null ? remapper.mapMethodName(indy.bsm.getOwner(), indy.bsm.getName(), indy.bsm.getDesc()) : indy.bsm.getName();
                        String bsmDesc = remapper != null ? remapper.mapMethodDesc(indy.bsm.getDesc()) : indy.bsm.getDesc();
                        VMMethodRef bsm = new VMMethodRef(bsmOwner, bsmName, bsmDesc, indy.bsm.getTag());
                        Object[] bsmArgs = new Object[indy.bsmArgs.length];
                        for (int i = 0; i < indy.bsmArgs.length; i++) {
                            Object arg = indy.bsmArgs[i];
                            if (arg instanceof org.objectweb.asm.Handle) {
                                org.objectweb.asm.Handle h = (org.objectweb.asm.Handle) arg;
                                String hOwner = remapper != null ? remapper.mapType(h.getOwner()) : h.getOwner();
                                String hName = remapper != null ? remapper.mapMethodName(h.getOwner(), h.getName(), h.getDesc()) : h.getName();
                                String hDesc = remapper != null ? remapper.mapMethodDesc(h.getDesc()) : h.getDesc();
                                arg = new VMMethodRef(hOwner, hName, hDesc, h.getTag());
                            } else if (arg instanceof Type) {
                                arg = remapper != null ? remapper.mapDesc(((Type) arg).getDescriptor()) : ((Type) arg).getDescriptor();
                            } else if (arg instanceof String && remapper != null) {
                                if (((String) arg).startsWith("L") && ((String) arg).endsWith(";")) {
                                    arg = remapper.mapDesc((String) arg);
                                } else if (((String) arg).contains("/")) {
                                    arg = remapper.mapType((String) arg);
                                }
                            }
                            bsmArgs[i] = arg;
                        }
                        String indyDesc = remapper != null ? remapper.mapMethodDesc(indy.desc) : indy.desc;
                        VMInvokeDynamicRef ref = new VMInvokeDynamicRef(indy.name, indyDesc, bsm, bsmArgs);
                        int idx = addConstant(constantPool, ref);
                        dos.writeShort(idx);
                        break;
                    }

                    default:
                        throw new IllegalStateException("Unhandled standard VM opcode: " + stdOp);
                }
            }
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] bytecode = bos.toByteArray();
        VMBlock vmBlock = new VMBlock(mn.maxStack + 2, mn.maxLocals + 2, bytecode, constantPool.toArray());
        return vmBlock.toBytes();
    }

    private static int addConstant(List<Object> pool, Object val) {
        int idx = pool.indexOf(val);
        if (idx == -1) {
            idx = pool.size();
            pool.add(val);
        }
        return idx;
    }

    private static int getInstructionSize(AbstractInsnNode insn) {
        if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
            return 0;
        }
        int op = insn.getOpcode();
        switch (op) {
            case Opcodes.ACONST_NULL:
                return 1;
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                return 5; // converted to VM_ICONST (opcode + 4 bytes)

            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
                return 9; // converted to VM_LCONST (opcode + 8 bytes)

            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2:
                return 5; // converted to VM_FCONST (opcode + 4 bytes)

            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1:
                return 9; // converted to VM_DCONST (opcode + 8 bytes)

            case Opcodes.LDC:
            case Opcodes.INVOKESTATIC:
            case Opcodes.INVOKEVIRTUAL:
            case Opcodes.INVOKESPECIAL:
            case Opcodes.INVOKEINTERFACE:
            case Opcodes.GETSTATIC:
            case Opcodes.PUTSTATIC:
            case Opcodes.GETFIELD:
            case Opcodes.PUTFIELD:
            case Opcodes.INVOKEDYNAMIC:
                return 3; // opcode + short index (2 bytes)

            case Opcodes.ILOAD:
            case Opcodes.LLOAD:
            case Opcodes.FLOAD:
            case Opcodes.DLOAD:
            case Opcodes.ALOAD:
            case Opcodes.ISTORE:
            case Opcodes.LSTORE:
            case Opcodes.FSTORE:
            case Opcodes.DSTORE:
            case Opcodes.ASTORE:
                return 2; // opcode + index (1 byte)

            case Opcodes.IADD:
            case Opcodes.ISUB:
            case Opcodes.IMUL:
            case Opcodes.IDIV:
            case Opcodes.LADD:
            case Opcodes.LSUB:
            case Opcodes.LMUL:
            case Opcodes.LDIV:
            case Opcodes.I2L:
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.ARETURN:
            case Opcodes.RETURN:
                return 1;

            case Opcodes.IINC:
                return 3;

            case Opcodes.GOTO:
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                return 3;

            default:
                throw new IllegalArgumentException("Unsupported JVM instruction opcode: " + op);
        }
    }

    private static int mapToStdOpcode(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        switch (op) {
            case Opcodes.NOP: return VMInterpreter.VM_NOP;
            case Opcodes.ACONST_NULL: return VMInterpreter.VM_ACONST_NULL;
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                return VMInterpreter.VM_ICONST;

            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
                return VMInterpreter.VM_LCONST;

            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2:
                return VMInterpreter.VM_FCONST;

            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1:
                return VMInterpreter.VM_DCONST;

            case Opcodes.ILOAD: return VMInterpreter.VM_ILOAD;
            case Opcodes.LLOAD: return VMInterpreter.VM_LLOAD;
            case Opcodes.FLOAD: return VMInterpreter.VM_FLOAD;
            case Opcodes.DLOAD: return VMInterpreter.VM_DLOAD;
            case Opcodes.ALOAD: return VMInterpreter.VM_ALOAD;
            case Opcodes.ISTORE: return VMInterpreter.VM_ISTORE;
            case Opcodes.LSTORE: return VMInterpreter.VM_LSTORE;
            case Opcodes.FSTORE: return VMInterpreter.VM_FSTORE;
            case Opcodes.DSTORE: return VMInterpreter.VM_DSTORE;
            case Opcodes.ASTORE: return VMInterpreter.VM_ASTORE;
            case Opcodes.IADD: return VMInterpreter.VM_IADD;
            case Opcodes.ISUB: return VMInterpreter.VM_ISUB;
            case Opcodes.IMUL: return VMInterpreter.VM_IMUL;
            case Opcodes.IDIV: return VMInterpreter.VM_IDIV;
            case Opcodes.LADD: return VMInterpreter.VM_LADD;
            case Opcodes.LSUB: return VMInterpreter.VM_LSUB;
            case Opcodes.LMUL: return VMInterpreter.VM_LMUL;
            case Opcodes.LDIV: return VMInterpreter.VM_LDIV;
            case Opcodes.I2L: return VMInterpreter.VM_I2L;
            case Opcodes.IINC: return VMInterpreter.VM_IINC;
            case Opcodes.GOTO: return VMInterpreter.VM_GOTO;
            case Opcodes.IFEQ: return VMInterpreter.VM_IFEQ;
            case Opcodes.IFNE: return VMInterpreter.VM_IFNE;
            case Opcodes.IFLT: return VMInterpreter.VM_IFLT;
            case Opcodes.IFGE: return VMInterpreter.VM_IFGE;
            case Opcodes.IFGT: return VMInterpreter.VM_IFGT;
            case Opcodes.IFLE: return VMInterpreter.VM_IFLE;
            case Opcodes.IF_ICMPEQ: return VMInterpreter.VM_IF_ICMPEQ;
            case Opcodes.IF_ICMPNE: return VMInterpreter.VM_IF_ICMPNE;
            case Opcodes.IF_ICMPLT: return VMInterpreter.VM_IF_ICMPLT;
            case Opcodes.IF_ICMPGE: return VMInterpreter.VM_IF_ICMPGE;
            case Opcodes.IF_ICMPGT: return VMInterpreter.VM_IF_ICMPGT;
            case Opcodes.IF_ICMPLE: return VMInterpreter.VM_IF_ICMPLE;
            case Opcodes.IF_ACMPEQ: return VMInterpreter.VM_IF_ACMPEQ;
            case Opcodes.IF_ACMPNE: return VMInterpreter.VM_IF_ACMPNE;
            case Opcodes.IFNULL: return VMInterpreter.VM_IFNULL;
            case Opcodes.IFNONNULL: return VMInterpreter.VM_IFNONNULL;
            case Opcodes.LDC: return VMInterpreter.VM_LDC;
            case Opcodes.INVOKESTATIC: return VMInterpreter.VM_INVOKESTATIC;
            case Opcodes.INVOKEVIRTUAL: return VMInterpreter.VM_INVOKEVIRTUAL;
            case Opcodes.INVOKESPECIAL: return VMInterpreter.VM_INVOKESPECIAL;
            case Opcodes.INVOKEINTERFACE: return VMInterpreter.VM_INVOKEINTERFACE;
            case Opcodes.GETSTATIC: return VMInterpreter.VM_GETSTATIC;
            case Opcodes.PUTSTATIC: return VMInterpreter.VM_PUTSTATIC;
            case Opcodes.GETFIELD: return VMInterpreter.VM_GETFIELD;
            case Opcodes.PUTFIELD: return VMInterpreter.VM_PUTFIELD;
            case Opcodes.IRETURN: return VMInterpreter.VM_IRETURN;
            case Opcodes.LRETURN: return VMInterpreter.VM_LRETURN;
            case Opcodes.ARETURN: return VMInterpreter.VM_ARETURN;
            case Opcodes.RETURN: return VMInterpreter.VM_RETURN;
            case Opcodes.INVOKEDYNAMIC: return VMInterpreter.VM_INVOKEDYNAMIC;
            default:
                throw new IllegalArgumentException("Unknown ASM instruction opcode: " + op);
        }
    }

    private static int getConstIntValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return op - Opcodes.ICONST_0;
        }
        if (insn instanceof IntInsnNode) {
            return ((IntInsnNode) insn).operand;
        }
        if (insn instanceof LdcInsnNode) {
            return ((Number) ((LdcInsnNode) insn).cst).intValue();
        }
        throw new IllegalArgumentException("Not an integer constant instruction");
    }

    private static long getConstLongValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.LCONST_0) return 0L;
        if (op == Opcodes.LCONST_1) return 1L;
        if (insn instanceof LdcInsnNode) {
            return ((Number) ((LdcInsnNode) insn).cst).longValue();
        }
        throw new IllegalArgumentException("Not a long constant instruction");
    }

    private static float getConstFloatValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.FCONST_0) return 0.0f;
        if (op == Opcodes.FCONST_1) return 1.0f;
        if (op == Opcodes.FCONST_2) return 2.0f;
        if (insn instanceof LdcInsnNode) {
            return ((Number) ((LdcInsnNode) insn).cst).floatValue();
        }
        throw new IllegalArgumentException("Not a float constant instruction");
    }

    private static double getConstDoubleValue(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.DCONST_0) return 0.0;
        if (op == Opcodes.DCONST_1) return 1.0;
        if (insn instanceof LdcInsnNode) {
            return ((Number) ((LdcInsnNode) insn).cst).doubleValue();
        }
        throw new IllegalArgumentException("Not a double constant instruction");
    }
}
