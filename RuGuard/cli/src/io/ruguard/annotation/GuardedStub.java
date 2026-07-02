package io.ruguard.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for the auto-generated stub that replaces every {@link Guarded}
 * method after transformation. The presence of this annotation lets the
 * runtime decoder distinguish stubs from regular code and apply the
 * decrypt-execute-wipe dance without consulting any registry.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface GuardedStub {
    /** Index of the encrypted payload block in the {@code RUGUARD_BLOCKS}
     *  static array of the host class. */
    int blockIndex();

    /** Stable identity tag for stack-walking diagnostics. */
    String id() default "";
}
