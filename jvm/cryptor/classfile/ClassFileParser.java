package crypting.classfile;


import crypting.constants.Annotations;
import crypting.constants.Constans;
import crypting.constants.Opcodes;
import crypting.reader.ClassFileStream;
import crypting.writer.ClassFileWriter;

import java.awt.image.Kernel;
import java.io.IOException;
import java.util.Random;

public class ClassFileParser implements Constans, Annotations, Opcodes {

    private final ClassFileStream classFileStream;
    private final ClassFileWriter classFileWriter;
    int key = 0;

    private String[] utf8;

    public ClassFileParser(final ClassFileStream classFileStream, final ClassFileWriter classFileWriter) {
        this.classFileStream = classFileStream;
        this.classFileWriter = classFileWriter;
    }

    public void parseStream() throws IOException {
        this.classFileStream.readInt();
//        this.classFileWriter.writeInt(0xCAFEBABE);
        classFileWriter.setKey(key = new Random().nextInt(40)+1,
                new Random().nextInt(40)+1,
                new Random().nextInt(40)+1,
                new Random().nextInt(40)+1);

        // minor && major

        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort());

        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort());

        final int constant_pool_count = this.classFileStream.readUnsignedShort();
        this.classFileWriter.writeXorShort(constant_pool_count);

        this.parseConstantPoolEntries(constant_pool_count);

        int flags = classFileStream.readUnsignedShort();

        //flags
        this.classFileWriter.writeXorShort(flags);

        //this_class_index
        this.classFileWriter.writeXorShort(classFileStream.readUnsignedShort());

        //super
        this.classFileWriter.writeXorShort(classFileStream.readUnsignedShort());

        int _itfs_len = classFileStream.readUnsignedShort();
        this.classFileWriter.writeXorShort(_itfs_len);

        parseInterfaces(_itfs_len);

        int fields_count = classFileStream.getReader().readUnsignedShort();
        this.classFileWriter.writeXorShort(fields_count);

        parseFields(fields_count);

        int methods_counts = classFileStream.getReader().readUnsignedShort();
        this.classFileWriter.writeXorShort(methods_counts);

        parseMethods(methods_counts);

        int attributesCount = classFileStream.getReader().readUnsignedShort();
        this.classFileWriter.writeXorShort(attributesCount);
        if (attributesCount > 0) {
            while (attributesCount-- != 0) {
                int attribute_name_index = this.classFileStream.readUnsignedShort();
                int attribute_length = this.classFileStream.readInt();
                this.classFileWriter.writeShort(attribute_name_index);
                this.classFileWriter.writeInt(attribute_length);
                if (attribute_length > 0) {
                    byte[] info = new byte[attribute_length];
                    this.classFileStream.getReader().readFully(info);
                    classFileWriter.getByteArrayOutputStream().write(info);
                }
            }
        }

        //classFileWriter.writeByte(classFileWriter.getKey2());

    }

    public void parseConstantPoolEntries(final int length) {
        utf8 = new String[length];
        try {
            for (int i = 1; i < length; ++i) {
                final int byte1 = this.classFileStream.readByte();
                this.classFileWriter.writeTag(byte1);
                switch (byte1) {
                    case JVM_CONSTANT_Class:
                        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort());
                        break;
                    case JVM_CONSTANT_String:
                        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort());
                        break;
                    case JVM_CONSTANT_Fieldref:
                        final int short1 = this.classFileStream.readUnsignedShort();
                        final int short2 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short1);
                        this.classFileWriter.writeXorShort(short2);
                        break;
                    case JVM_CONSTANT_InterfaceMethodref:
                        final int short3 = this.classFileStream.readUnsignedShort();
                        final int short4 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short3);
                        this.classFileWriter.writeXorShort(short4);
                        break;
                    case JVM_CONSTANT_Methodref:
                        final int short5 = this.classFileStream.readUnsignedShort();
                        final int short6 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short5);
                        this.classFileWriter.writeXorShort(short6);
                        break;
                    case JVM_CONSTANT_NameAndType:
                        final int short7 = this.classFileStream.readUnsignedShort();
                        final int short8 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short7);
                        this.classFileWriter.writeXorShort(short8);
                        break;
                    case JVM_CONSTANT_MethodHandle:
                        int ref_kind = this.classFileStream.readByte();
                        final int index = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeByte(ref_kind ^ key);
                        this.classFileWriter.writeXorShort(index ^ key);
                        break;
                    case JVM_CONSTANT_MethodType:
                        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort() ^ key);
                        break;
                    case JVM_CONSTANT_Dynamic:
                        final int short10 = this.classFileStream.readUnsignedShort();
                        final int short11 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short10);
                        this.classFileWriter.writeXorShort(short11);
                        break;
                    case JVM_CONSTANT_InvokeDynamic:
                        final int short12 = this.classFileStream.readUnsignedShort();
                        final int short13 = this.classFileStream.readUnsignedShort();
                        this.classFileWriter.writeXorShort(short12);
                        this.classFileWriter.writeXorShort(short13);
                        break;
                    case JVM_CONSTANT_Integer:
                        this.classFileWriter.writeXorInt(this.classFileStream.readInt() ^ key);
                        break;
                    case JVM_CONSTANT_Float:
                        this.classFileWriter.writeXorInt(Float.floatToIntBits(this.classFileStream.readFloat()));
                        break;
                    case JVM_CONSTANT_Long:
                        this.classFileWriter.writeXorLong(this.classFileStream.readLong() ^ key);
                        ++i;
                        break;
                    case JVM_CONSTANT_Double:
                        this.classFileWriter.writeXorLong(Double.doubleToLongBits(this.classFileStream.readDouble()));
                        ++i;
                        break;
                    case JVM_CONSTANT_Utf8:
                        byte[] e = this.classFileStream.readUTF();
                        utf8[i] = new String(e);
                        this.classFileWriter.writeUTF(e, key);
                        break;
                    case JVM_CONSTANT_Module, JVM_CONSTANT_Package:
                        this.classFileWriter.writeXorShort(this.classFileStream.readUnsignedShort());
                        break;
                }
            }
        } catch (final Exception ex) {
            System.out.println(ex.getMessage());
        }
    }

    private void parseInterfaces(int _itfs_len) throws IOException {
        if (_itfs_len > 0) {
            for (int index = 0; index < _itfs_len; index++) {
                final int interface_index = classFileStream.readUnsignedShort();
                this.classFileWriter.writeXorShort(interface_index);
            }
        }
    }

    private void parseFields(int fields_count) throws IOException {
        if (fields_count > 0) {
            for (int i = 0; i < fields_count; i++) {
                int accessFlags = classFileStream.getReader().readUnsignedShort();
                int nameIndex = classFileStream.getReader().readUnsignedShort();
                int descriptorIndex = classFileStream.getReader().readUnsignedShort();
                classFileWriter.writeXorShort(accessFlags);
                classFileWriter.writeXorShort(nameIndex);
                classFileWriter.writeXorShort(descriptorIndex);
                int attributesCount = classFileStream.getReader().readUnsignedShort();
                classFileWriter.writeXorShort(attributesCount);
                for (int attr = 0; attr < attributesCount; attr++) {
                    parseAttribute();
                }
            }
        }
    }

    private void parseMethods(int method_counts) throws IOException {
        if (method_counts > 0) {
            for (int i = 0; i < method_counts; i++) {
                int accessFlags = classFileStream.getReader().readUnsignedShort();
                int nameIndex = classFileStream.getReader().readUnsignedShort();
                int descriptorIndex = classFileStream.getReader().readUnsignedShort();
                classFileWriter.writeXorShort(accessFlags);
                classFileWriter.writeXorShort(nameIndex);
                classFileWriter.writeXorShort(descriptorIndex);
                int attributesCount = classFileStream.getReader().readUnsignedShort();
                classFileWriter.writeXorShort(attributesCount);
                for(int attr = 0; attr < attributesCount; attr++) {
                    parseAttribute();
                }
            }
        }
    }

    private void parseCodeAttribute() throws IOException {
        int max_stack = this.classFileStream.readUnsignedShort();
        int max_locals = this.classFileStream.readUnsignedShort();
        int code_length = this.classFileStream.readInt();
        this.classFileWriter.writeXorShort(max_stack);
        this.classFileWriter.writeXorShort(max_locals);
        this.classFileWriter.writeXorInt(code_length);

        // bytecode instructions
        for (int i = 0; i < code_length; i++) {

            int opcode = this.classFileStream.readByte();

            this.classFileWriter.writeByte(opcode);
        }

        // exception table
        int exception_table_length = this.classFileStream.readUnsignedShort();
        this.classFileWriter.writeXorShort(exception_table_length);

        for (int i = 0; i < exception_table_length; i++) {
            for (int j = 0; j < 4; j++) {
                this.classFileWriter.writeShort(this.classFileStream.readUnsignedShort());
            }
        }

        // attributes
        int attributes_count = this.classFileStream.readUnsignedShort();
        this.classFileWriter.writeXorShort(attributes_count);

        for (int i = 0; i < attributes_count; i++) {
            int attributeNameIndex = this.classFileStream.readUnsignedShort();
            int attributeLength = this.classFileStream.readInt();
            this.classFileWriter.writeXorShort(attributeNameIndex);
            this.classFileWriter.writeXorInt(attributeLength);
            for (int j = 0; j < attributeLength; j++) {
                this.classFileWriter.writeByte(this.classFileStream.readByte());
            }
        }
    }

    private void parseAttribute() throws IOException {
        int attribute_name_index = this.classFileStream.readUnsignedShort();
        int attribute_length = this.classFileStream.readInt();
        this.classFileWriter.writeShort(attribute_name_index);
        this.classFileWriter.writeInt(attribute_length);
        if (utf8[attribute_name_index].equals("Code")) {
            parseCodeAttribute();
        } else {
            if (attribute_length > 0) {
                byte[] info = new byte[attribute_length];
                this.classFileStream.getReader().readFully(info);
                classFileWriter.getByteArrayOutputStream().write(info);
            }
        }
    }

}
