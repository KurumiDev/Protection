import org.objectweb.asm.*;
import java.io.*;

public class DumpEvents {
    public static void main(String[] args) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("D:\\Protection\\RuGuard\\llllIIl.class"));
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                System.out.println("Class: " + name + " (super: " + superName + ")");
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                System.out.println("Method: " + name + " " + descriptor);
                return null;
            }
        }, 0);
    }
}
