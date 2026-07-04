import java.util.regex.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestEntryPoints {
    public static void main(String[] args) throws Exception {
        byte[] fabricJson = Files.readAllBytes(Paths.get("D:\\Protection\\RuGuard\\original_classes\\fabric.mod.json"));
        String json = new String(fabricJson, java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"([a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+)\"").matcher(json);
        while (m.find()) {
            System.out.println("EntryPoint: " + m.group(1).replace('.', '/'));
        }
    }
}
