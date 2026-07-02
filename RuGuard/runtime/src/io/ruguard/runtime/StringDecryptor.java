package io.ruguard.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Empty wrapper class for string decryption.
 * The actual decryption logic is injected dynamically as an inline
 * static method ($sd) into each protected class by StringEncryptor.
 * This class is just here to satisfy any theoretical IDE/compile
 * dependency, though we don't actually use it since we generate
 * the bytecodes directly.
 */
public final class StringDecryptor {
    private StringDecryptor() {}
}
