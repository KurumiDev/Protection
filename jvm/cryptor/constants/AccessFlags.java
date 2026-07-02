package crypting.constants;

public interface AccessFlags {
    int JVM_ACC_PUBLIC        = 0x0001;
    int JVM_ACC_PRIVATE       = 0x0002;
    int JVM_ACC_PROTECTED     = 0x0004;
    int JVM_ACC_STATIC        = 0x0008;
    int JVM_ACC_FINAL         = 0x0010;
    int JVM_ACC_SYNCHRONIZED  = 0x0020;
    int JVM_ACC_SUPER         = 0x0020;
    int JVM_ACC_VOLATILE      = 0x0040;
    int JVM_ACC_BRIDGE        = 0x0040;
    int JVM_ACC_TRANSIENT     = 0x0080;
    int JVM_ACC_VARARGS       = 0x0080;
    int JVM_ACC_NATIVE        = 0x0100;
    int JVM_ACC_INTERFACE     = 0x0200;
    int JVM_ACC_ABSTRACT      = 0x0400;
    int JVM_ACC_STRICT        = 0x0800;
    int JVM_ACC_SYNTHETIC     = 0x1000;
    int JVM_ACC_ANNOTATION    = 0x2000;
    int JVM_ACC_ENUM          = 0x4000;
    int JVM_ACC_MODULE        = 0x8000;
}
