package wissendGuard.natives.manipulation.byteProcess;

import wissendGuard.natives.manipulation.StartPlatform;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

public class PreprocessorRunner {

    private final static List<Preprocessor> PREPROCESSORS = new ArrayList<>();

    static {
        PREPROCESSORS.add(new IndyPreprocessor());
        PREPROCESSORS.add(new LdcPreprocessor());
    }

    public static void preprocess(ClassNode classNode, MethodNode methodNode, StartPlatform startPlatform) {
        for (Preprocessor preprocessor : PREPROCESSORS) {
            preprocessor.process(classNode, methodNode, startPlatform);
        }
    }
}
