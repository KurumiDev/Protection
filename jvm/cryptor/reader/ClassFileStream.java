package crypting.reader;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

public final class ClassFileStream {
    private final DataInputStream reader;

    public ClassFileStream(final byte[] buf) {
        this.reader = new DataInputStream(new ByteArrayInputStream(buf));
    }

    public int readByte() throws IOException {
        return reader.read();
    }

    public int readShort() throws IOException {
        return this.reader.readShort();
    }

    public int readUnsignedShort() throws IOException {
        return this.reader.readUnsignedShort();
    }

    public int readInt() throws IOException {
        return this.reader.readInt();
    }

    public long readLong() throws IOException {
        return this.reader.readLong();
    }

    public float readFloat() throws IOException {
        return this.reader.readFloat();
    }

    public double readDouble() throws IOException {
        return this.reader.readDouble();
    }

    public byte[] readUTF() throws IOException {
        final byte[] array = new byte[this.reader.readUnsignedShort()];
        this.reader.readFully(array);
        return array;
    }

    public DataInputStream getReader() {
        return reader;
    }
}
