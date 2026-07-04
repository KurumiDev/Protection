import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
public class TestVMBlock {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, TestVMBlock.class.getClassLoader());

        Class<?> rcClass = classLoader.loadClass("io.ruguard.runtime.RuntimeContext");
        byte[] seed = new byte[8];
        rcClass.getMethod("initialise", byte[].class).invoke(null, seed);
        
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
