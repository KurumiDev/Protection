import java.lang.invoke.MethodHandles;

public class TestHiddenClassStackTrace {
    public static void main(String[] args) throws Exception {
        byte[] b = org.objectweb.asm.Opcodes.class.getClassLoader() == null ? null : dump();
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(b, true);
        try {
            lookup.lookupClass().getMethod("test").invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static byte[] dump() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "HiddenTest", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "test", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "java/lang/NoSuchMethodError");
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn("fakeMethod");
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/NoSuchMethodError", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
