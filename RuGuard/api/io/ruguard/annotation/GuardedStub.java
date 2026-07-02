package io.ruguard.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Эту аннотацию руками ставить НЕ НАДО.
 * Программа защиты сама повесит её на место твоего метода, когда заменит оригинальный код зашифрованной заглушкой.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface GuardedStub {
    int blockIndex();
    String id() default "";
}
