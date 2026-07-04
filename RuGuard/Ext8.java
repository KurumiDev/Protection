import java.io.*;
import java.util.zip.*;
public class Ext8 {
    public static void main(String[] args) throws Exception {
        ZipFile zip = new ZipFile("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        ZipEntry entry = zip.getEntry("a/b/IIlIIl.class");
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream("IIlIIl_test.class");
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close(); in.close(); zip.close();
    }
}
