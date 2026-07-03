package io.ruguard.runtime.vm;

import io.ruguard.runtime.RuntimeContext;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core VM Interpreter for RuGuard custom bytecode (Spec §7.2).
 */
public final class VMInterpreter {

    public static final int VM_NOP = 0;
    public static final int VM_ACONST_NULL = 1;
    public static final int VM_ICONST = 2;
    public static final int VM_LCONST = 3;
    public static final int VM_FCONST = 4;
    public static final int VM_DCONST = 5;
    public static final int VM_ILOAD = 6;
    public static final int VM_LLOAD = 7;
    public static final int VM_FLOAD = 8;
    public static final int VM_DLOAD = 9;
    public static final int VM_ALOAD = 10;
    public static final int VM_ISTORE = 11;
    public static final int VM_LSTORE = 12;
    public static final int VM_FSTORE = 13;
    public static final int VM_DSTORE = 14;
    public static final int VM_ASTORE = 15;
    public static final int VM_IADD = 16;
    public static final int VM_ISUB = 17;
    public static final int VM_IMUL = 18;
    public static final int VM_IDIV = 19;
    public static final int VM_LADD = 20;
    public static final int VM_LSUB = 21;
    public static final int VM_LMUL = 22;
    public static final int VM_LDIV = 23;
    public static final int VM_I2L = 24;
    public static final int VM_IINC = 25;
    public static final int VM_GOTO = 26;
    public static final int VM_IFEQ = 27;
    public static final int VM_IFNE = 28;
    public static final int VM_IFLT = 29;
    public static final int VM_IFGE = 30;
    public static final int VM_IFGT = 31;
    public static final int VM_IFLE = 32;
    public static final int VM_IF_ICMPEQ = 33;
    public static final int VM_IF_ICMPNE = 34;
    public static final int VM_IF_ICMPLT = 35;
    public static final int VM_IF_ICMPGE = 36;
    public static final int VM_IF_ICMPGT = 37;
    public static final int VM_IF_ICMPLE = 38;
    public static final int VM_IF_ACMPEQ = 39;
    public static final int VM_IF_ACMPNE = 40;
    public static final int VM_IFNULL = 41;
    public static final int VM_IFNONNULL = 42;
    public static final int VM_LDC = 43;
    public static final int VM_INVOKESTATIC = 44;
    public static final int VM_INVOKEVIRTUAL = 45;
    public static final int VM_INVOKESPECIAL = 46;
    public static final int VM_INVOKEINTERFACE = 47;
    public static final int VM_GETSTATIC = 48;
    public static final int VM_PUTSTATIC = 49;
    public static final int VM_GETFIELD = 50;
    public static final int VM_PUTFIELD = 51;
    public static final int VM_IRETURN = 52;
    public static final int VM_LRETURN = 53;
    public static final int VM_ARETURN = 54;
    public static final int VM_RETURN = 55;
    public static final int VM_INVOKEDYNAMIC = 56;

    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.lang.reflect.Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MethodHandle> INDY_CACHE = new ConcurrentHashMap<>();

    private VMInterpreter() {}

    /**
     * Entry point for VM execution: attempts JNI executeNative, falls back to pure Java execute.
     */
    public static Object executeVM(byte[] payload, Object[] args, Class<?> hostClass) {
        try {
            VMBlock block = VMBlock.fromBytes(payload);
            return io.ruguard.nativebridge.NativeVM.executeNative(
                    block.bytecode,
                    block.constantPool,
                    block.maxStack,
                    block.maxLocals,
                    args,
                    hostClass
            );
        } catch (Throwable t) {
            // Silent fallback to the pure-Java VM interpreter on linkage or
            // native-execution failure. Printing the trace would reveal the
            // native barrier and the VM payload layout.
            return execute(payload, args, hostClass);
        }
    }

    /**
     * Executes the custom VM payload. Called dynamically from the MethodHandle wrapper in EncryptedBlock.
     */
    public static Object execute(byte[] payload, Object[] args, Class<?> hostClass) {
        try {
            VMBlock block = VMBlock.fromBytes(payload);
            byte[] buildSeed = RuntimeContext.current().buildSeed();
            int[] opcodeMap = VMInstructionShuffler.getOpcodeMap(buildSeed);

            // Compute inverse mapping for fast runtime lookup
            int[] inverseMap = new int[256];
            for (int i = 0; i < 256; i++) {
                inverseMap[opcodeMap[i]] = i;
            }

            ClassLoader cl = hostClass.getClassLoader();

            Object[] locals = new Object[block.maxLocals];
            if (args != null) {
                System.arraycopy(args, 0, locals, 0, Math.min(args.length, locals.length));
            }

            Object[] stack = new Object[block.maxStack];
            int sp = 0;
            int ip = 0;
            byte[] bytecode = block.bytecode;

            while (ip < bytecode.length) {
                int instrStart = ip;
                int rawOpcode = bytecode[ip++] & 0xFF;
                int stdOpcode = inverseMap[rawOpcode];

                switch (stdOpcode) {
                    case VM_NOP:
                        break;

                    case VM_ACONST_NULL:
                        stack[sp++] = null;
                        break;

                    case VM_ICONST: {
                        int val = ((bytecode[ip++] & 0xFF) << 24) |
                                  ((bytecode[ip++] & 0xFF) << 16) |
                                  ((bytecode[ip++] & 0xFF) << 8) |
                                  (bytecode[ip++] & 0xFF);
                        stack[sp++] = val;
                        break;
                    }

                    case VM_LCONST: {
                        long val = 0;
                        for (int i = 0; i < 8; i++) {
                            val = (val << 8) | (bytecode[ip++] & 0xFF);
                        }
                        stack[sp++] = val;
                        break;
                    }

                    case VM_FCONST: {
                        int bits = ((bytecode[ip++] & 0xFF) << 24) |
                                   ((bytecode[ip++] & 0xFF) << 16) |
                                   ((bytecode[ip++] & 0xFF) << 8) |
                                   (bytecode[ip++] & 0xFF);
                        stack[sp++] = Float.intBitsToFloat(bits);
                        break;
                    }

                    case VM_DCONST: {
                        long bits = 0;
                        for (int i = 0; i < 8; i++) {
                            bits = (bits << 8) | (bytecode[ip++] & 0xFF);
                        }
                        stack[sp++] = Double.longBitsToDouble(bits);
                        break;
                    }

                    case VM_ILOAD:
                    case VM_LLOAD:
                    case VM_FLOAD:
                    case VM_DLOAD:
                    case VM_ALOAD: {
                        int idx = bytecode[ip++] & 0xFF;
                        stack[sp++] = locals[idx];
                        break;
                    }

                    case VM_ISTORE:
                    case VM_LSTORE:
                    case VM_FSTORE:
                    case VM_DSTORE:
                    case VM_ASTORE: {
                        int idx = bytecode[ip++] & 0xFF;
                        locals[idx] = stack[--sp];
                        break;
                    }

                    case VM_IADD: {
                        int b = ((Number) stack[--sp]).intValue();
                        int a = ((Number) stack[--sp]).intValue();
                        stack[sp++] = a + b;
                        break;
                    }

                    case VM_ISUB: {
                        int b = ((Number) stack[--sp]).intValue();
                        int a = ((Number) stack[--sp]).intValue();
                        stack[sp++] = a - b;
                        break;
                    }

                    case VM_IMUL: {
                        int b = ((Number) stack[--sp]).intValue();
                        int a = ((Number) stack[--sp]).intValue();
                        stack[sp++] = a * b;
                        break;
                    }

                    case VM_IDIV: {
                        int b = ((Number) stack[--sp]).intValue();
                        int a = ((Number) stack[--sp]).intValue();
                        stack[sp++] = a / b;
                        break;
                    }

                    case VM_LADD: {
                        long b = ((Number) stack[--sp]).longValue();
                        long a = ((Number) stack[--sp]).longValue();
                        stack[sp++] = a + b;
                        break;
                    }

                    case VM_LSUB: {
                        long b = ((Number) stack[--sp]).longValue();
                        long a = ((Number) stack[--sp]).longValue();
                        stack[sp++] = a - b;
                        break;
                    }

                    case VM_LMUL: {
                        long b = ((Number) stack[--sp]).longValue();
                        long a = ((Number) stack[--sp]).longValue();
                        stack[sp++] = a * b;
                        break;
                    }

                    case VM_LDIV: {
                        long b = ((Number) stack[--sp]).longValue();
                        long a = ((Number) stack[--sp]).longValue();
                        stack[sp++] = a / b;
                        break;
                    }

                    case VM_I2L: {
                        int val = ((Number) stack[--sp]).intValue();
                        stack[sp++] = (long) val;
                        break;
                    }

                    case VM_IINC: {
                        int idx = bytecode[ip++] & 0xFF;
                        int inc = bytecode[ip++]; // automatically sign-extended
                        locals[idx] = ((Number) locals[idx]).intValue() + inc;
                        break;
                    }

                    case VM_GOTO: {
                        int offset = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        offset = (short) offset;
                        ip = instrStart + offset;
                        break;
                    }

                    case VM_IFEQ:
                    case VM_IFNE:
                    case VM_IFLT:
                    case VM_IFGE:
                    case VM_IFGT:
                    case VM_IFLE: {
                        int offset = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        offset = (short) offset;
                        int val = ((Number) stack[--sp]).intValue();
                        boolean cond = false;
                        switch (stdOpcode) {
                            case VM_IFEQ: cond = (val == 0); break;
                            case VM_IFNE: cond = (val != 0); break;
                            case VM_IFLT: cond = (val < 0); break;
                            case VM_IFGE: cond = (val >= 0); break;
                            case VM_IFGT: cond = (val > 0); break;
                            case VM_IFLE: cond = (val <= 0); break;
                        }
                        if (cond) {
                            ip = instrStart + offset;
                        }
                        break;
                    }

                    case VM_IF_ICMPEQ:
                    case VM_IF_ICMPNE:
                    case VM_IF_ICMPLT:
                    case VM_IF_ICMPGE:
                    case VM_IF_ICMPGT:
                    case VM_IF_ICMPLE: {
                        int offset = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        offset = (short) offset;
                        int val2 = ((Number) stack[--sp]).intValue();
                        int val1 = ((Number) stack[--sp]).intValue();
                        boolean cond = false;
                        switch (stdOpcode) {
                            case VM_IF_ICMPEQ: cond = (val1 == val2); break;
                            case VM_IF_ICMPNE: cond = (val1 != val2); break;
                            case VM_IF_ICMPLT: cond = (val1 < val2); break;
                            case VM_IF_ICMPGE: cond = (val1 >= val2); break;
                            case VM_IF_ICMPGT: cond = (val1 > val2); break;
                            case VM_IF_ICMPLE: cond = (val1 <= val2); break;
                        }
                        if (cond) {
                            ip = instrStart + offset;
                        }
                        break;
                    }

                    case VM_IF_ACMPEQ:
                    case VM_IF_ACMPNE: {
                        int offset = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        offset = (short) offset;
                        Object val2 = stack[--sp];
                        Object val1 = stack[--sp];
                        boolean cond = (stdOpcode == VM_IF_ACMPEQ) ? (val1 == val2) : (val1 != val2);
                        if (cond) {
                            ip = instrStart + offset;
                        }
                        break;
                    }

                    case VM_IFNULL:
                    case VM_IFNONNULL: {
                        int offset = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        offset = (short) offset;
                        Object val = stack[--sp];
                        boolean cond = (stdOpcode == VM_IFNULL) ? (val == null) : (val != null);
                        if (cond) {
                            ip = instrStart + offset;
                        }
                        break;
                    }

                    case VM_LDC: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        stack[sp++] = block.constantPool[idx];
                        break;
                    }

                    case VM_INVOKESTATIC:
                    case VM_INVOKEVIRTUAL:
                    case VM_INVOKESPECIAL:
                    case VM_INVOKEINTERFACE: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMMethodRef ref = (VMMethodRef) block.constantPool[idx];
                        Class<?>[] params = getParamTypes(ref.desc, cl);
                        Object[] mArgs = new Object[params.length];
                        for (int i = params.length - 1; i >= 0; i--) {
                            mArgs[i] = stack[--sp];
                        }
                        Object instance = null;
                        if (stdOpcode != VM_INVOKESTATIC) {
                            instance = stack[--sp];
                        }
                        Method m = resolveMethod(ref.owner, ref.name, ref.desc, cl);
                        Object res = m.invoke(instance, mArgs);
                        if (m.getReturnType() != void.class) {
                            stack[sp++] = res;
                        }
                        break;
                    }

                    case VM_GETSTATIC: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMFieldRef ref = (VMFieldRef) block.constantPool[idx];
                        java.lang.reflect.Field f = resolveField(ref.owner, ref.name, cl);
                        stack[sp++] = f.get(null);
                        break;
                    }

                    case VM_PUTSTATIC: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMFieldRef ref = (VMFieldRef) block.constantPool[idx];
                        java.lang.reflect.Field f = resolveField(ref.owner, ref.name, cl);
                        f.set(null, stack[--sp]);
                        break;
                    }

                    case VM_GETFIELD: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMFieldRef ref = (VMFieldRef) block.constantPool[idx];
                        java.lang.reflect.Field f = resolveField(ref.owner, ref.name, cl);
                        Object instance = stack[--sp];
                        stack[sp++] = f.get(instance);
                        break;
                    }

                    case VM_PUTFIELD: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMFieldRef ref = (VMFieldRef) block.constantPool[idx];
                        java.lang.reflect.Field f = resolveField(ref.owner, ref.name, cl);
                        Object val = stack[--sp];
                        Object instance = stack[--sp];
                        f.set(instance, val);
                        break;
                    }

                    case VM_INVOKEDYNAMIC: {
                        int idx = ((bytecode[ip++] & 0xFF) << 8) | (bytecode[ip++] & 0xFF);
                        VMInvokeDynamicRef ref = (VMInvokeDynamicRef) block.constantPool[idx];
                        MethodHandle target = resolveIndy(ref, hostClass, cl);
                        Class<?>[] params = getParamTypes(ref.desc, cl);
                        Object[] mArgs = new Object[params.length];
                        for (int i = params.length - 1; i >= 0; i--) {
                            mArgs[i] = stack[--sp];
                        }
                        Object res = target.invokeWithArguments(mArgs);
                        if (target.type().returnType() != void.class) {
                            stack[sp++] = res;
                        }
                        break;
                    }

                    case VM_IRETURN:
                    case VM_LRETURN:
                    case VM_ARETURN:
                        return stack[--sp];

                    case VM_RETURN:
                        return null;

                    default:
                        throw new IllegalStateException("Unsupported VM opcode: " + stdOpcode);
                }
            }
            return null;
        } catch (Throwable t) {
            // P4 — Silent degradation: return a default fallback value or throw runtime if critical.
            // Under spec P4, we should degrade silently.
            throw new RuntimeException("VM interpretation failure", t);
        }
    }

    private static Method resolveMethod(String owner, String name, String desc, ClassLoader cl) throws Exception {
        String cacheKey = owner + "#" + name + "#" + desc;
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Class<?> clazz = Class.forName(owner.replace('/', '.'), false, cl);
        Class<?>[] params = getParamTypes(desc, cl);
        Method m = null;
        try {
            m = clazz.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            // Check public methods if not found in declared
            m = clazz.getMethod(name, params);
        }
        m.setAccessible(true);
        METHOD_CACHE.put(cacheKey, m);
        return m;
    }

    private static java.lang.reflect.Field resolveField(String owner, String name, ClassLoader cl) throws Exception {
        String cacheKey = owner + "#" + name;
        java.lang.reflect.Field cached = FIELD_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Class<?> clazz = Class.forName(owner.replace('/', '.'), false, cl);
        java.lang.reflect.Field f = null;
        try {
            f = clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = clazz.getField(name);
        }
        f.setAccessible(true);
        FIELD_CACHE.put(cacheKey, f);
        return f;
    }

    private static MethodHandle resolveIndy(VMInvokeDynamicRef ref, Class<?> hostClass, ClassLoader cl) throws Throwable {
        String cacheKey = hostClass.getName() + "#" + ref.name + "#" + ref.desc;
        MethodHandle cached = INDY_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Method bsmMethod = resolveMethod(ref.bsm.owner, ref.bsm.name, ref.bsm.desc, cl);
        MethodType mt = MethodType.fromMethodDescriptorString(ref.desc, cl);

        // Standard java invokedynamic bootstrap expects:
        // (Lookup, String, MethodType, bsmArgs...)
        MethodHandles.Lookup hostLookup = MethodHandles.lookup();
        try {
            // Attempt to gain nested lookup permissions if possible
            hostLookup = MethodHandles.privateLookupIn(hostClass, hostLookup);
        } catch (Exception ignored) {}

        Object[] bsmArgs = new Object[3 + ref.bsmArgs.length];
        bsmArgs[0] = hostLookup;
        bsmArgs[1] = ref.name;
        bsmArgs[2] = mt;
        for (int i = 0; i < ref.bsmArgs.length; i++) {
            Object arg = ref.bsmArgs[i];
            // Resolve bootstrap types
            if (arg instanceof String && ((String) arg).startsWith("L") && ((String) arg).endsWith(";")) {
                arg = Class.forName(((String) arg).substring(1, ((String) arg).length() - 1).replace('/', '.'), false, cl);
            }
            bsmArgs[3 + i] = arg;
        }

        CallSite cs = (CallSite) bsmMethod.invoke(null, bsmArgs);
        MethodHandle target = cs.dynamicInvoker();
        INDY_CACHE.put(cacheKey, target);
        return target;
    }

    private static Class<?>[] getParamTypes(String desc, ClassLoader cl) throws ClassNotFoundException {
        List<Class<?>> types = new ArrayList<>();
        int i = 1; // skip '('
        while (desc.charAt(i) != ')') {
            int count = 0;
            while (desc.charAt(i) == '[') {
                count++;
                i++;
            }
            Class<?> type;
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String name = desc.substring(i + 1, end).replace('/', '.');
                type = Class.forName(name, false, cl);
                i = end + 1;
            } else {
                type = getPrimitiveClass(c);
                i++;
            }
            for (int k = 0; k < count; k++) {
                type = java.lang.reflect.Array.newInstance(type, 0).getClass();
            }
            types.add(type);
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> getPrimitiveClass(char c) {
        switch (c) {
            case 'I': return int.class;
            case 'J': return long.class;
            case 'Z': return boolean.class;
            case 'C': return char.class;
            case 'B': return byte.class;
            case 'S': return short.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
        }
        throw new IllegalArgumentException("Unknown primitive type descriptor: " + c);
    }

    public static Object resolveAndInvoke(String owner, String name, String desc, Object instance, Object[] args, ClassLoader cl) throws Exception {
        Method m = resolveMethod(owner, name, desc, cl);
        return m.invoke(instance, args);
    }

    public static Object resolveAndGetField(String owner, String name, Object instance, ClassLoader cl) throws Exception {
        java.lang.reflect.Field f = resolveField(owner, name, cl);
        return f.get(instance);
    }

    public static void resolveAndSetField(String owner, String name, Object instance, Object value, ClassLoader cl) throws Exception {
        java.lang.reflect.Field f = resolveField(owner, name, cl);
        f.set(instance, value);
    }

    public static Object resolveAndInvokeIndy(Object indyRefObj, Class<?> hostClass, Object[] args, ClassLoader cl) throws Throwable {
        VMInvokeDynamicRef ref = (VMInvokeDynamicRef) indyRefObj;
        MethodHandle target = resolveIndy(ref, hostClass, cl);
        return target.invokeWithArguments(args);
    }
}
