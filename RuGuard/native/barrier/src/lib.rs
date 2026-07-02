use jni::sys::{jint, jbyteArray, jclass, jobject, jobjectArray, JNI_VERSION_1_8};
use jni::objects::{JClass, JObject, JObjectArray, JString, JByteArray, JValue};
use jni::{JavaVM, NativeMethod, JNIEnv};

mod secret;

#[no_mangle]
pub unsafe extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let mut env = vm.get_env().expect("Failed to get JNI Env");
    
    // 1. NativeEntropy
    let class_name_entropy = "io/ruguard/nativebridge/NativeEntropy";
    let class_entropy = env.find_class(class_name_entropy).expect("Failed to find NativeEntropy class");
    let method_entropy = NativeMethod {
        name: "currentEntropy".to_string().into(),
        sig: "()[B".to_string().into(),
        fn_ptr: current_entropy_native as *mut std::ffi::c_void,
    };
    env.register_native_methods(&class_entropy, &[method_entropy]).expect("Failed to register NativeEntropy method");
    
    // 2. NativeVM
    let class_name_vm = "io/ruguard/nativebridge/NativeVM";
    let class_vm = env.find_class(class_name_vm).expect("Failed to find NativeVM class");
    let method_vm = NativeMethod {
        name: "executeNative".to_string().into(),
        sig: "([B[Ljava/lang/Object;II[Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;".to_string().into(),
        fn_ptr: execute_native_native as *mut std::ffi::c_void,
    };
    env.register_native_methods(&class_vm, &[method_vm]).expect("Failed to register NativeVM method");
    
    JNI_VERSION_1_8
}


unsafe extern "system" fn current_entropy_native(env: JNIEnv, _class: JClass) -> jbyteArray {
    let debugged = run_anti_debug_checks();
    
    let mut entropy = secret::MASTER_SECRET;
    
    if debugged {
        // Quiet failure: alter a single byte silently (P4)
        entropy[0] ^= 0xAB;
    }
    
    let array = env.new_byte_array(entropy.len() as jint).unwrap();
    let slice: &[i8] = std::slice::from_raw_parts(entropy.as_ptr() as *const i8, entropy.len());
    env.set_byte_array_region(&array, 0, slice).unwrap();
    
    array.into_raw()
}

fn run_anti_debug_checks() -> bool {
    let mut triggers = Vec::new();
    unsafe {
        if windows_sys::Win32::System::Diagnostics::Debug::IsDebuggerPresent() != 0 {
            triggers.push("IsDebuggerPresent");
        }
        let mut remote_present = 0;
        let proc = windows_sys::Win32::System::Threading::GetCurrentProcess();
        if windows_sys::Win32::System::Diagnostics::Debug::CheckRemoteDebuggerPresent(proc, &mut remote_present) != 0 {
            if remote_present != 0 {
                triggers.push("CheckRemoteDebuggerPresent");
            }
        }
        let mut context: windows_sys::Win32::System::Diagnostics::Debug::CONTEXT = std::mem::zeroed();
        context.ContextFlags = windows_sys::Win32::System::Diagnostics::Debug::CONTEXT_DEBUG_REGISTERS_AMD64;
        let thread = windows_sys::Win32::System::Threading::GetCurrentThread();
        if windows_sys::Win32::System::Diagnostics::Debug::GetThreadContext(thread, &mut context) != 0 {
            if context.Dr0 != 0 || context.Dr1 != 0 || context.Dr2 != 0 || context.Dr3 != 0 {
                triggers.push("HardwareRegisters");
            }
        }
    }
    let start = unsafe { std::arch::x86_64::_rdtsc() };
    let mut sum = 0u64;
    for i in 0..500 {
        sum = sum.wrapping_add(i);
    }
    std::hint::black_box(sum);
    let end = unsafe { std::arch::x86_64::_rdtsc() };
    let diff = end - start;
    if diff > 80_000 {
        triggers.push("RDTSC");
    }
    if let Ok(mut f) = std::fs::File::create("D:\\Protection\\RuGuard\\anti_debug_triggered.txt") {
        use std::io::Write;
        let _ = writeln!(f, "Triggers: {:?}, RDTSC diff: {}", triggers, diff);
    }
    
    !triggers.is_empty()
}

unsafe extern "system" fn execute_native_native(
    mut env: JNIEnv,
    _class: JClass,
    bytecode: jobject,
    constant_pool: jobjectArray,
    max_stack: jint,
    max_locals: jint,
    args: jobjectArray,
    host_class: jclass,
) -> jobject {
    if let Ok(mut f) = std::fs::File::create("D:\\Protection\\RuGuard\\native_vm_executed.txt") {
        use std::io::Write;
        let _ = writeln!(f, "YES");
    }
    let bytecode_arr = JByteArray::from_raw(bytecode as jbyteArray);
    let constant_pool_arr = JObjectArray::from_raw(constant_pool);
    let args_arr = JObjectArray::from_raw(args);
    let host_class_obj = JClass::from_raw(host_class);

    match execute_vm_rust(&mut env, &bytecode_arr, &constant_pool_arr, max_stack, max_locals, &args_arr, &host_class_obj) {
        Ok(res) => res.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn get_string_field(env: &mut JNIEnv, obj: &JObject, name: &str) -> Result<String, jni::errors::Error> {
    let jstr = env.get_field(obj, name, "Ljava/lang/String;")?.l()?;
    let jstr: JString = jstr.into();
    let rstr: String = env.get_string(&jstr)?.into();
    Ok(rstr)
}

fn box_int<'local>(env: &mut JNIEnv<'local>, val: i32) -> Result<JObject<'local>, jni::errors::Error> {
    let class = env.find_class("java/lang/Integer")?;
    env.call_static_method(class, "valueOf", "(I)Ljava/lang/Integer;", &[JValue::Int(val)])?.l()
}

fn unbox_int<'local>(env: &mut JNIEnv<'local>, obj: &JObject<'local>) -> Result<i32, jni::errors::Error> {
    env.call_method(obj, "intValue", "()I", &[])?.i()
}

fn box_long<'local>(env: &mut JNIEnv<'local>, val: i64) -> Result<JObject<'local>, jni::errors::Error> {
    let class = env.find_class("java/lang/Long")?;
    env.call_static_method(class, "valueOf", "(J)Ljava/lang/Long;", &[JValue::Long(val)])?.l()
}

fn unbox_long<'local>(env: &mut JNIEnv<'local>, obj: &JObject<'local>) -> Result<i64, jni::errors::Error> {
    env.call_method(obj, "longValue", "()J", &[])?.j()
}

fn box_float<'local>(env: &mut JNIEnv<'local>, val: f32) -> Result<JObject<'local>, jni::errors::Error> {
    let class = env.find_class("java/lang/Float")?;
    env.call_static_method(class, "valueOf", "(F)Ljava/lang/Float;", &[JValue::Float(val)])?.l()
}

fn unbox_float<'local>(env: &mut JNIEnv<'local>, obj: &JObject<'local>) -> Result<f32, jni::errors::Error> {
    env.call_method(obj, "floatValue", "()F", &[])?.f()
}

fn box_double<'local>(env: &mut JNIEnv<'local>, val: f64) -> Result<JObject<'local>, jni::errors::Error> {
    let class = env.find_class("java/lang/Double")?;
    env.call_static_method(class, "valueOf", "(D)Ljava/lang/Double;", &[JValue::Double(val)])?.l()
}

fn unbox_double<'local>(env: &mut JNIEnv<'local>, obj: &JObject<'local>) -> Result<f64, jni::errors::Error> {
    env.call_method(obj, "doubleValue", "()D", &[])?.d()
}

struct JavaRandom {
    seed: u64,
}

impl JavaRandom {
    fn new(seed: u64) -> Self {
        JavaRandom {
            seed: (seed ^ 0x5DEECE66D) & ((1 << 48) - 1),
        }
    }

    fn next(&mut self, bits: u32) -> i32 {
        self.seed = (self.seed.wrapping_mul(0x5DEECE66D).wrapping_add(0xB)) & ((1 << 48) - 1);
        (self.seed >> (48 - bits)) as i32
    }

    fn next_int(&mut self, n: i32) -> i32 {
        if (n & -n) == n {
            return (((n as i64).wrapping_mul(self.next(31) as i64)) >> 31) as i32;
        }
        let mut bits;
        let mut val;
        loop {
            bits = self.next(31);
            val = bits % n;
            if bits.wrapping_sub(val).wrapping_add(n - 1) >= 0 {
                break;
            }
        }
        val
    }
}

fn get_opcode_map(build_seed: &[u8]) -> [u8; 256] {
    let mut map = [0u8; 256];
    for i in 0..256 {
        map[i] = i as u8;
    }
    let mut seed = 0u64;
    for &b in build_seed {
        seed = (seed << 8) | (b as u64);
    }
    let mut rnd = JavaRandom::new(seed);
    for i in (1..256).rev() {
        let j = rnd.next_int(i as i32 + 1) as usize;
        map.swap(i, j);
    }
    map
}

fn get_param_count(desc: &str) -> usize {
    let mut count = 0;
    let mut chars = desc.chars();
    if chars.next() != Some('(') {
        return 0;
    }
    while let Some(c) = chars.next() {
        if c == ')' {
            break;
        }
        if c == '[' {
            continue;
        }
        if c == 'L' {
            for nc in chars.by_ref() {
                if nc == ';' {
                    break;
                }
            }
        }
        count += 1;
    }
    count
}

fn execute_vm_rust<'local>(
    env: &mut JNIEnv<'local>,
    bytecode_arr: &JByteArray<'local>,
    constant_pool: &JObjectArray<'local>,
    max_stack: jint,
    max_locals: jint,
    args: &JObjectArray<'local>,
    host_class: &JClass<'local>,
) -> Result<JObject<'local>, jni::errors::Error> {
    let bytecode: Vec<u8> = env.convert_byte_array(bytecode_arr)?;
    
    // Get build seed via JNI call to RuntimeContext
    let ctx_class = env.find_class("io/ruguard/runtime/RuntimeContext")?;
    let ctx = env.call_static_method(ctx_class, "current", "()Lio/ruguard/runtime/RuntimeContext;", &[])?.l()?;
    let seed_jarr = env.call_method(&ctx, "buildSeed", "()[B", &[])?.l()?;
    let seed_jarr: JByteArray = seed_jarr.into();
    let build_seed = env.convert_byte_array(&seed_jarr)?;
    
    let map = get_opcode_map(&build_seed);
    let mut inverse_map = [0u8; 256];
    for i in 0..256 {
        inverse_map[map[i] as usize] = i as u8;
    }
    
    let mut locals = vec![std::ptr::null_mut(); max_locals as usize];
    let args_len = env.get_array_length(args)?;
    for i in 0..args_len {
        if i < max_locals {
            let arg = env.get_object_array_element(args, i)?;
            locals[i as usize] = arg.into_raw();
        }
    }
    
    let mut stack = vec![std::ptr::null_mut(); max_stack as usize];
    let mut sp = 0;
    let mut ip = 0;
    
    // Define standard opcodes matching VMInterpreter.java constants
    const VM_NOP: u8 = 0;
    const VM_ACONST_NULL: u8 = 1;
    const VM_ICONST: u8 = 2;
    const VM_LCONST: u8 = 3;
    const VM_FCONST: u8 = 4;
    const VM_DCONST: u8 = 5;
    const VM_ILOAD: u8 = 6;
    const VM_LLOAD: u8 = 7;
    const VM_FLOAD: u8 = 8;
    const VM_DLOAD: u8 = 9;
    const VM_ALOAD: u8 = 10;
    const VM_ISTORE: u8 = 11;
    const VM_LSTORE: u8 = 12;
    const VM_FSTORE: u8 = 13;
    const VM_DSTORE: u8 = 14;
    const VM_ASTORE: u8 = 15;
    const VM_IADD: u8 = 16;
    const VM_ISUB: u8 = 17;
    const VM_IMUL: u8 = 18;
    const VM_IDIV: u8 = 19;
    const VM_LADD: u8 = 20;
    const VM_LSUB: u8 = 21;
    const VM_LMUL: u8 = 22;
    const VM_LDIV: u8 = 23;
    const VM_I2L: u8 = 24;
    const VM_IINC: u8 = 25;
    const VM_GOTO: u8 = 26;
    const VM_IFEQ: u8 = 27;
    const VM_IFNE: u8 = 28;
    const VM_IFLT: u8 = 29;
    const VM_IFGE: u8 = 30;
    const VM_IFGT: u8 = 31;
    const VM_IFLE: u8 = 32;
    const VM_IF_ICMPEQ: u8 = 33;
    const VM_IF_ICMPNE: u8 = 34;
    const VM_IF_ICMPLT: u8 = 35;
    const VM_IF_ICMPGE: u8 = 36;
    const VM_IF_ICMPGT: u8 = 37;
    const VM_IF_ICMPLE: u8 = 38;
    const VM_IF_ACMPEQ: u8 = 39;
    const VM_IF_ACMPNE: u8 = 40;
    const VM_IFNULL: u8 = 41;
    const VM_IFNONNULL: u8 = 42;
    const VM_LDC: u8 = 43;
    const VM_INVOKESTATIC: u8 = 44;
    const VM_INVOKEVIRTUAL: u8 = 45;
    const VM_INVOKESPECIAL: u8 = 46;
    const VM_INVOKEINTERFACE: u8 = 47;
    const VM_GETSTATIC: u8 = 48;
    const VM_PUTSTATIC: u8 = 49;
    const VM_GETFIELD: u8 = 50;
    const VM_PUTFIELD: u8 = 51;
    const VM_IRETURN: u8 = 52;
    const VM_LRETURN: u8 = 53;
    const VM_ARETURN: u8 = 54;
    const VM_RETURN: u8 = 55;
    const VM_INVOKEDYNAMIC: u8 = 56;

    let cl = env.call_method(host_class, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])?.l()?;
    let vm_interpreter_class = env.find_class("io/ruguard/runtime/vm/VMInterpreter")?;

    while ip < bytecode.len() {
        let instr_start = ip;
        let raw_opcode = bytecode[ip] as usize;
        ip += 1;
        let std_opcode = inverse_map[raw_opcode];

        match std_opcode {
            VM_NOP => {}
            VM_ACONST_NULL => {
                stack[sp] = std::ptr::null_mut();
                sp += 1;
            }
            VM_ICONST => {
                let val = ((bytecode[ip] as i32) << 24) |
                          ((bytecode[ip+1] as i32) << 16) |
                          ((bytecode[ip+2] as i32) << 8) |
                          (bytecode[ip+3] as i32);
                ip += 4;
                let val_obj = box_int(env, val)?;
                stack[sp] = val_obj.into_raw();
                sp += 1;
            }
            VM_LCONST => {
                let mut val = 0u64;
                for i in 0..8 {
                    val = (val << 8) | (bytecode[ip+i] as u64);
                }
                ip += 8;
                let val_obj = box_long(env, val as i64)?;
                stack[sp] = val_obj.into_raw();
                sp += 1;
            }
            VM_FCONST => {
                let bits = ((bytecode[ip] as i32) << 24) |
                           ((bytecode[ip+1] as i32) << 16) |
                           ((bytecode[ip+2] as i32) << 8) |
                           (bytecode[ip+3] as i32);
                ip += 4;
                let val_obj = box_float(env, f32::from_bits(bits as u32))?;
                stack[sp] = val_obj.into_raw();
                sp += 1;
            }
            VM_DCONST => {
                let mut bits = 0u64;
                for i in 0..8 {
                    bits = (bits << 8) | (bytecode[ip+i] as u64);
                }
                ip += 8;
                let val_obj = box_double(env, f64::from_bits(bits))?;
                stack[sp] = val_obj.into_raw();
                sp += 1;
            }
            VM_ILOAD | VM_LLOAD | VM_FLOAD | VM_DLOAD | VM_ALOAD => {
                let idx = bytecode[ip] as usize;
                ip += 1;
                stack[sp] = locals[idx];
                sp += 1;
            }
            VM_ISTORE | VM_LSTORE | VM_FSTORE | VM_DSTORE | VM_ASTORE => {
                let idx = bytecode[ip] as usize;
                ip += 1;
                locals[idx] = stack[sp - 1];
                sp -= 1;
            }
            VM_IADD | VM_ISUB | VM_IMUL | VM_IDIV => {
                let b_ptr = stack[sp - 1];
                let a_ptr = stack[sp - 2];
                sp -= 2;
                let b_obj = unsafe { JObject::from_raw(b_ptr) };
                let a_obj = unsafe { JObject::from_raw(a_ptr) };
                let b = unbox_int(env, &b_obj)?;
                let a = unbox_int(env, &a_obj)?;
                let res = match std_opcode {
                    VM_IADD => a.wrapping_add(b),
                    VM_ISUB => a.wrapping_sub(b),
                    VM_IMUL => a.wrapping_mul(b),
                    VM_IDIV => a / b,
                    _ => 0,
                };
                let res_obj = box_int(env, res)?;
                stack[sp] = res_obj.into_raw();
                sp += 1;
            }
            VM_LADD | VM_LSUB | VM_LMUL | VM_LDIV => {
                let b_ptr = stack[sp - 1];
                let a_ptr = stack[sp - 2];
                sp -= 2;
                let b_obj = unsafe { JObject::from_raw(b_ptr) };
                let a_obj = unsafe { JObject::from_raw(a_ptr) };
                let b = unbox_long(env, &b_obj)?;
                let a = unbox_long(env, &a_obj)?;
                let res = match std_opcode {
                    VM_LADD => a.wrapping_add(b),
                    VM_LSUB => a.wrapping_sub(b),
                    VM_LMUL => a.wrapping_mul(b),
                    VM_LDIV => a / b,
                    _ => 0,
                };
                let res_obj = box_long(env, res)?;
                stack[sp] = res_obj.into_raw();
                sp += 1;
            }
            VM_I2L => {
                let val_ptr = stack[sp - 1];
                sp -= 1;
                let val_obj = unsafe { JObject::from_raw(val_ptr) };
                let val = unbox_int(env, &val_obj)?;
                let res_obj = box_long(env, val as i64)?;
                stack[sp] = res_obj.into_raw();
                sp += 1;
            }
            VM_IINC => {
                let idx = bytecode[ip] as usize;
                let inc = bytecode[ip+1] as i8 as i32;
                ip += 2;
                let current_obj = unsafe { JObject::from_raw(locals[idx]) };
                let current = unbox_int(env, &current_obj)?;
                let res_obj = box_int(env, current + inc)?;
                locals[idx] = res_obj.into_raw();
            }
            VM_GOTO => {
                let offset = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                let offset = offset as i16 as isize;
                ip = (instr_start as isize + offset) as usize;
            }
            VM_IFEQ | VM_IFNE | VM_IFLT | VM_IFGE | VM_IFGT | VM_IFLE => {
                let offset = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let offset = offset as i16 as isize;
                let val_ptr = stack[sp - 1];
                sp -= 1;
                let val_obj = unsafe { JObject::from_raw(val_ptr) };
                let val = unbox_int(env, &val_obj)?;
                let cond = match std_opcode {
                    VM_IFEQ => val == 0,
                    VM_IFNE => val != 0,
                    VM_IFLT => val < 0,
                    VM_IFGE => val >= 0,
                    VM_IFGT => val > 0,
                    VM_IFLE => val <= 0,
                    _ => false,
                };
                if cond {
                    ip = (instr_start as isize + offset) as usize;
                }
            }
            VM_IF_ICMPEQ | VM_IF_ICMPNE | VM_IF_ICMPLT | VM_IF_ICMPGE | VM_IF_ICMPGT | VM_IF_ICMPLE => {
                let offset = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let offset = offset as i16 as isize;
                let val2_ptr = stack[sp - 1];
                let val1_ptr = stack[sp - 2];
                sp -= 2;
                let val2_obj = unsafe { JObject::from_raw(val2_ptr) };
                let val1_obj = unsafe { JObject::from_raw(val1_ptr) };
                let val2 = unbox_int(env, &val2_obj)?;
                let val1 = unbox_int(env, &val1_obj)?;
                let cond = match std_opcode {
                    VM_IF_ICMPEQ => val1 == val2,
                    VM_IF_ICMPNE => val1 != val2,
                    VM_IF_ICMPLT => val1 < val2,
                    VM_IF_ICMPGE => val1 >= val2,
                    VM_IF_ICMPGT => val1 > val2,
                    VM_IF_ICMPLE => val1 <= val2,
                    _ => false,
                };
                if cond {
                    ip = (instr_start as isize + offset) as usize;
                }
            }
            VM_IF_ACMPEQ | VM_IF_ACMPNE => {
                let offset = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let offset = offset as i16 as isize;
                let val2_ptr = stack[sp - 1];
                let val1_ptr = stack[sp - 2];
                sp -= 2;
                let val2_obj = unsafe { JObject::from_raw(val2_ptr) };
                let val1_obj = unsafe { JObject::from_raw(val1_ptr) };
                let is_same = env.is_same_object(&val1_obj, &val2_obj)?;
                let cond = if std_opcode == VM_IF_ACMPEQ { is_same } else { !is_same };
                if cond {
                    ip = (instr_start as isize + offset) as usize;
                }
            }
            VM_IFNULL | VM_IFNONNULL => {
                let offset = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let offset = offset as i16 as isize;
                let val_ptr = stack[sp - 1];
                sp -= 1;
                let val_obj = unsafe { JObject::from_raw(val_ptr) };
                let is_null = val_obj.is_null();
                let cond = if std_opcode == VM_IFNULL { is_null } else { !is_null };
                if cond {
                    ip = (instr_start as isize + offset) as usize;
                }
            }
            VM_LDC => {
                let idx = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let val = env.get_object_array_element(constant_pool, idx as jint)?;
                stack[sp] = val.into_raw();
                sp += 1;
            }
            VM_INVOKESTATIC | VM_INVOKEVIRTUAL | VM_INVOKESPECIAL | VM_INVOKEINTERFACE => {
                let idx = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let ref_obj = env.get_object_array_element(constant_pool, idx as jint)?;
                let owner = get_string_field(env, &ref_obj, "owner")?;
                let name = get_string_field(env, &ref_obj, "name")?;
                let desc = get_string_field(env, &ref_obj, "desc")?;

                let p_count = get_param_count(&desc);
                let mut m_args = vec![std::ptr::null_mut(); p_count];
                for i in (0..p_count).rev() {
                    m_args[i] = stack[sp - 1];
                    sp -= 1;
                }
                let args_jarr = env.new_object_array(p_count as jint, "java/lang/Object", JObject::null())?;
                for i in 0..p_count {
                    let arg_obj = unsafe { JObject::from_raw(m_args[i]) };
                    env.set_object_array_element(&args_jarr, i as jint, &arg_obj)?;
                }
                let mut instance_ptr = std::ptr::null_mut();
                if std_opcode != VM_INVOKESTATIC {
                    instance_ptr = stack[sp - 1];
                    sp -= 1;
                }
                let instance_obj = unsafe { JObject::from_raw(instance_ptr) };
                let res = env.call_static_method(
                    &vm_interpreter_class,
                    "resolveAndInvoke",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/ClassLoader;)Ljava/lang/Object;",
                    &[
                        JValue::Object(&env.new_string(&owner)?.into()),
                        JValue::Object(&env.new_string(&name)?.into()),
                        JValue::Object(&env.new_string(&desc)?.into()),
                        JValue::Object(&instance_obj),
                        JValue::Object(&args_jarr),
                        JValue::Object(&cl),
                    ],
                )?.l()?;
                if !desc.ends_with(")V") {
                    stack[sp] = res.into_raw();
                    sp += 1;
                }
            }
            VM_GETSTATIC | VM_GETFIELD => {
                let idx = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let ref_obj = env.get_object_array_element(constant_pool, idx as jint)?;
                let owner = get_string_field(env, &ref_obj, "owner")?;
                let name = get_string_field(env, &ref_obj, "name")?;

                let mut instance_ptr = std::ptr::null_mut();
                if std_opcode == VM_GETFIELD {
                    instance_ptr = stack[sp - 1];
                    sp -= 1;
                }
                let instance_obj = unsafe { JObject::from_raw(instance_ptr) };
                let val = env.call_static_method(
                    &vm_interpreter_class,
                    "resolveAndGetField",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/ClassLoader;)Ljava/lang/Object;",
                    &[
                        JValue::Object(&env.new_string(&owner)?.into()),
                        JValue::Object(&env.new_string(&name)?.into()),
                        JValue::Object(&instance_obj),
                        JValue::Object(&cl),
                    ],
                )?.l()?;
                stack[sp] = val.into_raw();
                sp += 1;
            }
            VM_PUTSTATIC | VM_PUTFIELD => {
                let idx = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let ref_obj = env.get_object_array_element(constant_pool, idx as jint)?;
                let owner = get_string_field(env, &ref_obj, "owner")?;
                let name = get_string_field(env, &ref_obj, "name")?;

                let val_ptr = stack[sp - 1];
                sp -= 1;
                let val_obj = unsafe { JObject::from_raw(val_ptr) };
                let mut instance_ptr = std::ptr::null_mut();
                if std_opcode == VM_PUTFIELD {
                    instance_ptr = stack[sp - 1];
                    sp -= 1;
                }
                let instance_obj = unsafe { JObject::from_raw(instance_ptr) };
                env.call_static_method(
                    &vm_interpreter_class,
                    "resolveAndSetField",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/ClassLoader;)V",
                    &[
                        JValue::Object(&env.new_string(&owner)?.into()),
                        JValue::Object(&env.new_string(&name)?.into()),
                        JValue::Object(&instance_obj),
                        JValue::Object(&val_obj),
                        JValue::Object(&cl),
                    ],
                )?;
            }
            VM_INVOKEDYNAMIC => {
                let idx = ((bytecode[ip] as u16) << 8) | (bytecode[ip+1] as u16);
                ip += 2;
                let ref_obj = env.get_object_array_element(constant_pool, idx as jint)?;
                let desc = get_string_field(env, &ref_obj, "desc")?;

                let p_count = get_param_count(&desc);
                let mut m_args = vec![std::ptr::null_mut(); p_count];
                for i in (0..p_count).rev() {
                    m_args[i] = stack[sp - 1];
                    sp -= 1;
                }
                let args_jarr = env.new_object_array(p_count as jint, "java/lang/Object", JObject::null())?;
                for i in 0..p_count {
                    let arg_obj = unsafe { JObject::from_raw(m_args[i]) };
                    env.set_object_array_element(&args_jarr, i as jint, &arg_obj)?;
                }
                let res = env.call_static_method(
                    &vm_interpreter_class,
                    "resolveAndInvokeIndy",
                    "(Ljava/lang/Object;Ljava/lang/Class;[Ljava/lang/Object;Ljava/lang/ClassLoader;)Ljava/lang/Object;",
                    &[
                        JValue::Object(&ref_obj),
                        JValue::Object(host_class),
                        JValue::Object(&args_jarr),
                        JValue::Object(&cl),
                    ],
                )?.l()?;
                if !desc.ends_with(")V") {
                    stack[sp] = res.into_raw();
                    sp += 1;
                }
            }
            VM_IRETURN | VM_LRETURN | VM_ARETURN => {
                let ret_ptr = stack[sp - 1];
                let ret_obj = unsafe { JObject::from_raw(ret_ptr) };
                return Ok(ret_obj);
            }
            VM_RETURN => {
                return Ok(JObject::null());
            }
            _ => {
                let msg = format!("Unsupported VM opcode: {}", std_opcode);
                let _ = env.throw_new("java/lang/IllegalStateException", msg);
                return Ok(JObject::null());
            }
        }
    }
    Ok(JObject::null())
}

