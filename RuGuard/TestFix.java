import io.ruguard.runtime.RuntimeContext;
import io.ruguard.runtime.vm.VMInterpreter;
import java.lang.reflect.Method;

public class TestFix {
    public static void main(String[] args) throws Exception {
        System.out.println("Testing fix with correct seed...");
        
        // Seed from protect_cataclysm.ps1: "1234567890abcdef"
        String hex = "1234567890abcdef";
        byte[] seed = new byte[8];
        for (int i = 0; i < 8; i++) {
            seed[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        Method init = RuntimeContext.class.getMethod("initialise", byte[].class);
        init.setAccessible(true);
        init.invoke(null, seed);
        
        // Get the dispatcher
        Class<?> dispatcherClass = Class.forName("a.b.IIlIIl");
        Method dispatch = dispatcherClass.getMethod("dispatch", int.class, Object[].class);
        
        // Block 10 in a.b.llllIIl is likely something from TextFactoryEvent. 
        // We just need a block index that exists, maybe block 0.
        // Actually, let's just instantiate a.b.llllIIl
        Class<?> textEvent = Class.forName("a.b.llllIIl");
        try {
            Object obj = textEvent.getConstructor(String.class).newInstance("hello");
            System.out.println("Successfully instantiated TextFactoryEvent: " + obj);
            
            Method getText = textEvent.getMethod("getText");
            Object result = getText.invoke(obj);
            System.out.println("getText() returned: " + result);
            
            if ("hello".equals(result)) {
                System.out.println("SUCCESS! Fix is working!");
            } else {
                System.out.println("FAILED! result != hello");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
