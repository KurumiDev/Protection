package crypting.writer;


import crypting.constants.Constans;

import java.io.IOException;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Random;

import static crypting.constants.Constans.*;

public final class ClassFileWriter implements Constans
{
   private final ByteArrayOutputStream baos;
   private final DataOutputStream writer;

   private int key;

   private int key_2;

   private int[] keys;

   private int absolute_key;

   public int pack(int number, int _key) {
      return (~
              (~number ^
                      (~_key >> 1))
                           ^ ((~_key ^ 0xFFFF)
                                 % 0xFFFF));
   }

   public void setKey(int key,
                      int key_2,
                      int key_3,
                      int key_4) {
      this.key = key;

      keys = new int[5];

      keys[0] = key;
      keys[1] = key_2;
      keys[2] = key_3;
      keys[3] = key_4;

      writeByte(keys[0]);
      writeByte(keys[1]);
      writeByte(keys[2]);
      writeByte(keys[3]);

      int high_and_low = pack(keys[0], keys[2] / keys[1])+pack(keys[3], ((keys[2] ^ keys[1]) % keys[0]) ^ keys[2]/2);
      absolute_key = high_and_low;
   }

   public static short shiftRight(int value) {
      return (short) ((short) ((value ^ 2
              << (2 % 32)
              ^ 0xFD)
              >> 2));
   }

   public int getKey() {
      return key;
   }

   public ClassFileWriter() {
      this.writer = new DataOutputStream(this.baos = new ByteArrayOutputStream());
   }


   public void writeByte(final int b){
       try {
           this.writer.write(b);
       } catch (IOException e) {
           throw new RuntimeException(e);
       }
   }


   public void writeTag(int tag) throws IOException {

      switch (tag) {
         case JVM_CONSTANT_Utf8 -> tag = 90;
         case JVM_CONSTANT_Integer -> tag = 22;
         case JVM_CONSTANT_MethodType -> tag = 23;
         case JVM_CONSTANT_Long -> tag = 24;
         case JVM_CONSTANT_MethodHandle -> tag = 25;
         case JVM_CONSTANT_NameAndType -> tag = 26;
//         case JVM_CONSTANT_Methodref -> tag = 27;
      }

       this.writer.writeByte(tag);
   }


   public void writeXorByte(final int b){
       try {
           this.writer.write(b);
       } catch (IOException e) {
           throw new RuntimeException(e);
       }
   }

   public void writeShort(final int v) throws IOException {
      this.writer.writeShort(v);
   }

   public void writeXorShort(final int val) throws IOException {
      int temp = new Random().nextInt(10);
      this.writer.writeShort((~(val) ^ absolute_key) ^ pack(temp, absolute_key ^ pack(keys[1], absolute_key))
              ^ ~key_2);
      writeInt(temp);
      key_2 = 0xFF ^ key;
      writeShort(key_2);
   }

   public void writeXorInt(final int v) throws IOException {
      this.writer.writeInt(v);
   }
   public void writeXorLong(final long v) throws IOException {
      this.writer.writeLong(v);
   }

   public void writeInt(final int v) throws IOException {
      this.writer.writeInt(v);
   }

   public void writeLong(final long v) throws IOException {
      this.writer.writeLong(v);
   }


   public void writeFloat(final float v) throws IOException {
      this.writer.writeFloat(v);
   }

   public void writeDouble(final double v) throws IOException {
      this.writer.writeDouble(v);
   }

   public byte[] rnfBytes(byte[] data, int key) {
      for (int i = 0; i < data.length; i++) {
         data[i] ^= (byte) key;
      }
      return data;
   }

   public void writeUTF(byte[] data, int key) throws IOException {
      writeXorShort(data.length);
      this.writer.write(rnfBytes(data, key));
   }

   public void write(ClassFileWriter classFileWriter) throws IOException {
      writer.write((classFileWriter.getByteArrayOutputStream().toByteArray()));
   }

   public ByteArrayOutputStream getByteArrayOutputStream() {
      return this.baos;
   }
}
