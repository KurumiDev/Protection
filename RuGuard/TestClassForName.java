import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
public class TestClassForName {
    public static void main(String[] args) throws Exception {
        File jarFile = new File("D:\\Protection\\RuGuard\\out\\protected-cataclysm-fixed.jar");
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, TestClassForName.class.getClassLoader());

        try {
            Class.forName("code.cataclysm.events.render.TextFactoryEvent", false, classLoader);
            System.out.println("FOUND!");
        } catch (Exception e) {
            System.out.println("NOT FOUND: " + e.getClass().getName());
        }
    }
}
