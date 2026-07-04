package io.ruguard.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker applied at build time to a class that has been processed by
 * {@code :ruguard-cli}. The runtime class-loader uses it to decide whether to
 * swap in the encrypted-block decoder machinery for stub methods.
 *
 * <p>The annotation also pins the build identifier ({@link #buildSeed()}) so
 * that every class knows which permutation of the bootstrap pipeline it was
 * produced by — used by the redteam harness to verify per-build polymorphism
 * (Spec §9.1, P5).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RuguardProtected {
    /** 64-bit seed that drives ISA permutation, chunk ordering, opaque-predicate
     *  selection and integrity-check scheduling for this build. */
    long buildSeed();

    /** Hex representation of the build seed - used for runtime context initialization
     *  when running outside RuguardClassLoader. */
    String buildSeedHex() default "";

    /** Hex representation of the encrypted-blocks fingerprint — useful for
     *  reproducible crash dumps and redteam builds. */
    String fingerprint() default "";
}
