package crypting.main;

import config.ConfigManager;
import crypting.classfile.ClassFileParser;
import crypting.reader.ClassFileStream;
import crypting.writer.ClassFileWriter;


import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


public final class ClassFileTransformer {
   private static final ConcurrentHashMap<String, byte[]> files = new ConcurrentHashMap();

    public static int unknown_offset = 0;

    public static int shadow_u1(byte value) {
        long key_1 = 10;
        long key_2 = 20;
        long key_3 = 50;
        int v3 = (int) ((key_1 + 1) & (key_1 + key_3) * unknown_offset);
        unknown_offset = v3;
        return (byte) (value ^ (v3 >> ((key_2) - (key_2))));
    }


    public static void main() throws IOException {
//        System.out.println(unshadow_u1((byte) 0xFB));
//       System.exit(0);

//      if (args.length < 1) {
//         System.err.println("java -jar protect.jar name.jar");
//      } else {

         ZipFile zip = new ZipFile("C:\\SovokApi\\Files\\native\\" + ConfigManager.nativeOutput + "\\client\\client-obf.jar");

         try {
             zip.entries().asIterator().forEachRemaining((entry) -> {

               try {

                  files.put(entry.getName(), zip.getInputStream(entry).readAllBytes());

               } catch (IOException var3) {
                  var3.printStackTrace();
               }

            });
         } catch (Throwable var7) {
            try {
               zip.close();
            } catch (Throwable var4) {
               var7.addSuppressed(var4);
            }

            throw var7;
         }

         zip.close();
         files.forEach((name, data) -> {
            if (isClassFileFormat(name, data)) {
               ClassFileWriter classFileWriter = new ClassFileWriter();
               ClassFileParser classFile = new ClassFileParser(new ClassFileStream(data), classFileWriter);

               try {
                   System.out.println(name);
                  classFile.parseStream();
               } catch (IOException var5) {
                  var5.printStackTrace();
               }

               byte[] buffer = classFileWriter.getByteArrayOutputStream().toByteArray();

               files.put(name, buffer);

            }

         });
         
         ZipOutputStream zos = new ZipOutputStream(new FileOutputStream("C:\\SovokApi\\Files\\success\\client.jar"));

         try {
            zos.setLevel(9);
            files.forEach((name, data) -> {
               try {
                  zos.putNextEntry(new ZipEntry(name));
                  zos.write(data);
                  zos.closeEntry();
               } catch (Exception var4) {
                  var4.printStackTrace();
               }

            });
         } catch (Throwable var6) {
            try {
               zos.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }

            throw var6;
         }

         zos.close();
//      }
   }

   private static boolean isClassFileFormat(String name, byte[] data) {
       boolean first = data.length >= 8 && name.endsWith(".class");
      return first;
   }
}
