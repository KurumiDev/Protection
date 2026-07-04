import java.io.*;
import java.util.zip.*;
public class Ext3 {
    public static void main(String[] args) throws Exception {
        ZipFile zip = new ZipFile("D:\\Protection\\RuGuard\\cataclysm-2.0-DEV (2).jar");
        ZipEntry entry = zip.getEntry("a/b/llllIIl.class");
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream("llllIIl_orig.class");
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close(); in.close(); zip.close();
    }
}
