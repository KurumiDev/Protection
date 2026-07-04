import io.ruguard.runtime.RuntimeContext;
import java.lang.reflect.Method;

public class TestFix3 {
    public static void main(String[] args) throws Exception {
        System.out.println("Testing fix with correct seed...");
        
        String hex = "1234567890abcdef";
        byte[] seed = new byte[8];
        for (int i = 0; i < 8; i++) {
            seed[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        Method init = RuntimeContext.class.getMethod("initialise", byte[].class);
        init.setAccessible(true);
        init.invoke(null, seed);
        
        Class<?> textEvent = Class.forName("a.b.llllIIl");
        try {
            Object obj = textEvent.getConstructor(String.class).newInstance("hello world");
            System.out.println("Successfully instantiated TextFactoryEvent: " + obj);
            
            Method targetMethod = null;
            for (Method m : textEvent.getDeclaredMethods()) {
                if (m.getReturnType() == String.class && m.getParameterCount() == 0) {
                    targetMethod = m;
                    break;
                }
            }
            
            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                Object result = targetMethod.invoke(obj);
                System.out.println("targetMethod returned: " + result);
                
                if ("hello world".equals(result)) {
                    System.out.println("SUCCESS! Fix is working!");
                } else {
                    System.out.println("FAILED! result != hello world");
                }
            } else {
                System.out.println("FAILED! Could not find method returning String");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
