import java.io.*;
import java.util.zip.*;
public class Ext5 {
    public static void main(String[] args) throws Exception {
        ZipFile zip = new ZipFile("D:\\Protection\\RuGuard\\cataclysm-2.0-DEV (2).jar");
        ZipEntry entry = zip.getEntry("code/cataclysm/mixins/TextVisitFactoryMixin.class");
        InputStream in = zip.getInputStream(entry);
        FileOutputStream out = new FileOutputStream("TextVisitFactoryMixin_orig.class");
        byte[] buf = new byte[1024]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close(); in.close(); zip.close();
    }
}
