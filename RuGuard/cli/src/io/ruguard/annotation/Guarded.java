package io.ruguard.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (or whole class) whose bytecode must be encrypted by the
 * {@code :ruguard-cli} build-time pass. Methods carrying this annotation are
 * moved into an encrypted sub-class file at build time and re-materialised only
 * on demand during execution.
 *
 * <p>Build-time contract:
 * <ul>
 *   <li>The class containing the annotated method must be package-private or
 *       public, and must be loadable by the runtime {@code :ruguard-bootstrap}
 *       class-loader.</li>
 *   <li>Inner-class, native, abstract and {@code <clinit>} members cannot be
 *       {@code @Guarded}.</li>
 *   <li>The {@link io.ruguard.annotation.Guarded.Level} controls how aggressive
 *       the transformation is — {@link Level#STRONG} applies control-flow
 *       flattening in addition to encryption.</li>
 * </ul>
 *
 * <p>Runtime contract: the original method is replaced by a stub that loads
 * the encrypted bytecode, decrypts it via HKDF-derived keys derived from
 * {@code io.ruguard.runtime.RuntimeContext}, calls the real implementation via
 * {@code MethodHandles.Lookup.defineClass()}, and zeroes the in-memory copy
 * once execution returns.
 *
 * <p>Spec refs: RuGuard-spec §6.1, §6.2, §6.3.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Guarded {
    enum Level { STANDARD, STRONG, VIRTUALIZED }
    Level value() default Level.STANDARD;
}
