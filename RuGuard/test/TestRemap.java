package test;
import org.objectweb.asm.commons.Remapper;
import java.util.Map;
import java.util.HashMap;

public class TestRemap {
    public static void main(String[] args) {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("code/cataclysm/events/render/TextFactoryEvent", "a/b/llllIIl");
        mapping.put("code/cataclysm/events/render/TextFactoryEvent.text.Ljava/lang/String;", "abc");
        
        Remapper r = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String key = owner + "." + name + "." + descriptor;
                if (mapping.containsKey(key)) return mapping.get(key);
                return name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String key = owner + "." + name + "." + descriptor;
                if (mapping.containsKey(key)) return mapping.get(key);
                return name;
            }

            @Override
            public String map(String internalName) {
                if (mapping.containsKey(internalName)) return mapping.get(internalName);
                return internalName;
            }
        };
        
        System.out.println("owner: " + r.mapType("code/cataclysm/events/render/TextFactoryEvent"));
        System.out.println("name: " + r.mapFieldName("code/cataclysm/events/render/TextFactoryEvent", "text", "Ljava/lang/String;"));
        System.out.println("desc: " + r.mapDesc("(Lcode/cataclysm/events/render/TextFactoryEvent;)V"));
    }
}
