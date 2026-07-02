package wissendGuard.natives.manipulation.special;

import wissendGuard.natives.methodHandler.MethodContext;

public interface SpecialMethodProcessor {
    String preProcess(MethodContext context);
    void postProcess(MethodContext context);
}
