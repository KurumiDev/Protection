package io.ruguard.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Эту аннотацию руками ставить НЕ НАДО.
 * Программа защиты сама вешает её на класс, который был успешно защищён.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RuguardProtected {
    long buildSeed();
    String buildSeedHex() default "";
    String fingerprint() default "";
}
