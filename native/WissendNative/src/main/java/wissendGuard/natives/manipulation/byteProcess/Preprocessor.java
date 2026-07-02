package wissendGuard.natives.manipulation.byteProcess;

import wissendGuard.natives.manipulation.StartPlatform;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public interface Preprocessor {

    void process(ClassNode classNode, MethodNode methodNode, StartPlatform startPlatform);
}
