import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
public class TestDirectInvoke {
    public static void main(String[] args) throws Throwable {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, TestDirectInvoke.class.getClassLoader());

        Class<?> rcClass = classLoader.loadClass("io.ruguard.runtime.RuntimeContext");
        byte[] seed = new byte[8];
        String hex = "c1ba0f85e3e50468";
        for (int i = 0; i < 8; i++) seed[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        rcClass.getMethod("initialise", byte[].class).invoke(null, seed);
        byte[] secret = new byte[32];
        String secretHex = "7f684a6da09c7b2f19c269d543bf74043f0e2e0b78e5312b0b7daf3449f180fd";
        for (int i = 0; i < 32; i++) secret[i] = (byte) Integer.parseInt(secretHex.substring(i * 2, i * 2 + 2), 16);
        Object rc = rcClass.getMethod("current").invoke(null);
        rcClass.getMethod("setNativeEntropy", byte[].class).invoke(rc, secret);

        Class<?> host = classLoader.loadClass("a.b.llllIIl");
        java.lang.reflect.Field f = host.getDeclaredField("RUGUARD_BLOCKS");
        f.setAccessible(true);
        byte[][] arr = (byte[][]) f.get(null);
        
        java.lang.reflect.Field fl = host.getDeclaredField("RUGUARD_LOOKUP");
        fl.setAccessible(true);
        java.lang.invoke.MethodHandles.Lookup lookup = (java.lang.invoke.MethodHandles.Lookup) fl.get(null);
        
        byte[] payload = arr[2];
        
        Class<?> ebClass = classLoader.loadClass("a.b.iiIIlIl"); // EncryptedBlock
        Object eb = ebClass.getConstructor(int.class, byte[].class, java.lang.invoke.MethodHandles.Lookup.class).newInstance(2, payload, lookup);
        
        Object mh = ebClass.getMethod("IiIlliIi").invoke(eb); // handle()
        System.out.println("MethodHandle: " + mh);
        
        java.lang.invoke.MethodHandle handle = (java.lang.invoke.MethodHandle) mh;
        Object result = handle.invokeWithArguments(host.getConstructor(String.class).newInstance("test_text"));
        System.out.println("Invoke returned: " + result);
    }
}
