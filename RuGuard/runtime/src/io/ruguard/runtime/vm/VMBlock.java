package io.ruguard.runtime.vm;

import java.io.*;

/**
 * Parses the custom VM binary block format (Magic 0x766d6263, stack size, local count, bytecode size, etc.).
 */
public final class VMBlock {
    public static final int MAGIC = 0x766d6263; // "vmbc" in hex

    public final int maxStack;
    public final int maxLocals;
    public final byte[] bytecode;
    public final Object[] constantPool;

    public VMBlock(int maxStack, int maxLocals, byte[] bytecode, Object[] constantPool) {
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        this.bytecode = bytecode;
        this.constantPool = constantPool;
    }

    /**
     * Serializes this VMBlock into a byte array.
     */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);

            dos.writeInt(MAGIC);
            dos.writeInt(maxStack);
            dos.writeInt(maxLocals);
            dos.writeInt(bytecode.length);
            dos.write(bytecode);

            // Simple object serialization for constant pool
            ByteArrayOutputStream objBos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(objBos);
            oos.writeInt(constantPool.length);
            for (Object obj : constantPool) {
                oos.writeObject(obj);
            }
            oos.flush();

            byte[] objBytes = objBos.toByteArray();
            dos.writeInt(objBytes.length);
            dos.write(objBytes);
            dos.flush();

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Serialization failure", e);
        }
    }

    /**
     * Checks if the decrypted bytes start with the VMBlock magic.
     */
    public static boolean isVMBlock(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        int m = ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
        return m == MAGIC;
    }

    /**
     * Parses a VMBlock from serialized bytes.
     */
    public static VMBlock fromBytes(byte[] bytes) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            int magic = dis.readInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("Invalid magic header: " + String.format("%08x", magic));
            }
            int maxStack = dis.readInt();
            int maxLocals = dis.readInt();
            int codeLen = dis.readInt();
            byte[] bytecode = new byte[codeLen];
            dis.readFully(bytecode);

            int objLen = dis.readInt();
            byte[] objBytes = new byte[objLen];
            dis.readFully(objBytes);

            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(objBytes));
            int poolLen = ois.readInt();
            Object[] pool = new Object[poolLen];
            for (int i = 0; i < poolLen; i++) {
                pool[i] = ois.readObject();
            }

            return new VMBlock(maxStack, maxLocals, bytecode, pool);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }
}
