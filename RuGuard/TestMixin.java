import java.lang.reflect.Method;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

public class TestMixin {
    public static void main(String[] args) throws Exception {
        // Load the protected jar
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, TestMixin.class.getClassLoader());

        // Initialise RuntimeContext
        byte[] seed = new byte[8];
        String hex = "c1ba0f85e3e50468";
        for (int i = 0; i < 8; i++) {
            seed[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        Class<?> runtimeContextClass = classLoader.loadClass("io.ruguard.runtime.RuntimeContext");
        Method initMethod = runtimeContextClass.getMethod("initialise", byte[].class);
        initMethod.invoke(null, seed);
        
        byte[] secret = new byte[32];
        String secretHex = "7f684a6da09c7b2f19c269d543bf74043f0e2e0b78e5312b0b7daf3449f180fd";
        for (int i = 0; i < 32; i++) {
            secret[i] = (byte) Integer.parseInt(secretHex.substring(i * 2, i * 2 + 2), 16);
        }
        
        Method currentMethod = runtimeContextClass.getMethod("current");
        Object rc = currentMethod.invoke(null);
        Method setNativeEntropy = runtimeContextClass.getMethod("setNativeEntropy", byte[].class);
        setNativeEntropy.invoke(rc, secret);

        // Call the method
        System.out.println("Loading TextVisitFactoryMixin...");
        Class<?> mixinClass = classLoader.loadClass("code.cataclysm.mixins.TextVisitFactoryMixin");
        Method adjustText = mixinClass.getDeclaredMethod("adjustText", String.class);
        adjustText.setAccessible(true);
        
        Class<?> targetClass = classLoader.loadClass("a.b.llllIIl");
        Method[] targetMethods = targetClass.getDeclaredMethods();
        for (Method tm : targetMethods) {
            if (tm.getName().equals("IlIIllIi")) {
                System.out.println("IlIIllIi param count: " + tm.getParameterCount());
                for(Class<?> p : tm.getParameterTypes()) {
                     System.out.println("  Param: " + p.getName());
                }
                System.out.println("  Return: " + tm.getReturnType().getName());
            }
        }
        
        System.out.println("Calling adjustText(null)...");
        try {
            Object result = adjustText.invoke(null, (String) null);
            System.out.println("Result: " + result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println("Calling adjustText(\"hello\")...");
        try {
            Object result = adjustText.invoke(null, "hello");
            System.out.println("Result: " + result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
