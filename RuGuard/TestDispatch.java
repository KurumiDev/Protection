import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
public class TestDispatch {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, TestDispatch.class.getClassLoader());

        // Init rc
        byte[] seed = new byte[8];
        String hex = "c1ba0f85e3e50468";
        for (int i = 0; i < 8; i++) seed[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        Class<?> rcClass = classLoader.loadClass("io.ruguard.runtime.RuntimeContext");
        rcClass.getMethod("initialise", byte[].class).invoke(null, seed);
        
        byte[] secret = new byte[32];
        String secretHex = "7f684a6da09c7b2f19c269d543bf74043f0e2e0b78e5312b0b7daf3449f180fd";
        for (int i = 0; i < 32; i++) secret[i] = (byte) Integer.parseInt(secretHex.substring(i * 2, i * 2 + 2), 16);
        Object rc = rcClass.getMethod("current").invoke(null);
        rcClass.getMethod("setNativeEntropy", byte[].class).invoke(rc, secret);

        System.out.println("Calling IIlIIl.dispatch...");
        Class<?> dispatchClass = classLoader.loadClass("a.b.IIlIIl");
        Method dispatch = dispatchClass.getMethod("dispatch", Class.class, int.class, Object[].class);
        
        Class<?> host = classLoader.loadClass("a.b.llllIIl");
        try {
            Object result = dispatch.invoke(null, host, 2, new Object[]{null});
            System.out.println("Dispatch returned: " + result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
