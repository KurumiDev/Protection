package io.ruguard.runtime.vm;
public class VMInterpreter {
    public static Object executeVM(byte[] payload, Object[] args, Class<?> hostClass) {
        System.out.println("MY executeVM CALLED! hostClass: " + hostClass.getName());
        return "fake_result";
    }
}
