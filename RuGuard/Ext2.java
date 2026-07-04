import java.io.*;
import java.util.zip.*;
public class Ext2 {
    public static void main(String[] args) throws Exception {
        ZipFile zip = new ZipFile("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        ZipEntry entry = zip.getEntry("a/b/illIll.class");
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream("illIll.class");
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close(); in.close(); zip.close();
    }
}
