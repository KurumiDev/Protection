package io.ruguard.runtime.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Crypto primitives shared between {@code :ruguard-cli} (build time) and
 * {@code :ruguard-runtime} (load + execution time). Built on BouncyCastle so
 * the two ends agree byte-for-byte and we have a single audited implementation
 * for both directions.
 *
 * <p>Spec refs: §6.1 (ChaCha20-Poly1305 chunk crypto), §6.2 (HKDF over
 * composite runtime context), §6.4 (per-build master secret), §9.1 (per-build
 * seed driving the permutation).
 *
 * <p><b>Compatibility:</b> BouncyCastle 1.78, JDK 21.
 */
public final class CryptoSuite {

    /** 256-bit ChaCha20 key. */
    public static final int KEY_BYTES = 32;
    /** 96-bit AEAD nonce. */
    public static final int NONCE_BYTES = 12;
    /** 128-bit authentication tag. */
    public static final int TAG_BYTES = 16;
    /** 256-bit HKDF output — one shot, used as the key for one ChaCha20 chunk. */
    public static final int DERIVED_KEY_BYTES = 32;

    private CryptoSuite() {}

    /** Securely random 32-byte key. */
    public static byte[] randomKey() {
        byte[] k = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(k);
        return k;
    }

    /**
     * HKDF-Extract-and-Expand (RFC 5869) on SHA-256. {@code salt} may be null.
     */
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        HKDFBytesGenerator gen = new HKDFBytesGenerator(new SHA256Digest());
        gen.init(new HKDFParameters(ikm, salt, info));
        byte[] okm = new byte[len];
        gen.generateBytes(okm, 0, len);
        return okm;
    }

    /**
     * AEAD-decrypt: input includes 16-byte tag at the end. Returns plaintext of
     * length {@code ctWithTag.length - 16}. On MAC failure we honour P4 and
     * return a deterministic-looking-but-incorrect payload — never an exception.
     */
    public static byte[] aeadDecrypt(byte[] key, byte[] nonce, byte[] ad, byte[] ctWithTag) {
        int payloadLen = ctWithTag.length - TAG_BYTES;
        // BC ChaCha20Poly1305 expects ALL bytes (ct+tag) through processBytes;
        // doFinal then verifies the tag and outputs the last plaintext chunk.
        byte[] buf = new byte[ctWithTag.length];
        ChaCha20Poly1305 aead = new ChaCha20Poly1305();
        aead.init(false, new AEADParameters(new KeyParameter(key), TAG_BYTES * 8, nonce, ad));
        int written = aead.processBytes(ctWithTag, 0, ctWithTag.length, buf, 0);
        try {
            written += aead.doFinal(buf, written);
        } catch (Exception ex) {
            // P4 — silent degradation. Caller sees a structurally valid but
            // wrong payload, looking like a buggy result, not a protection.
            Arrays.fill(buf, 0, payloadLen, (byte) 0xCC);
        }
        return Arrays.copyOf(buf, payloadLen);
    }

    public static byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] ad, byte[] pt) {
        try {
            ChaCha20Poly1305 aead = new ChaCha20Poly1305();
            aead.init(true, new AEADParameters(new KeyParameter(key), TAG_BYTES * 8, nonce, ad));
            byte[] out = new byte[pt.length + TAG_BYTES];
            int written = aead.processBytes(pt, 0, pt.length, out, 0);
            aead.doFinal(out, written);
            return out;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt block", ex);
        }
    }

    /** Deterministic 96-bit nonce derived from a per-build seed and chunk idx. */
    public static byte[] chunkNonce(byte[] buildSeed, int chunkIndex) {
        byte[] info = ("ruguard/nonce/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ikm = new byte[buildSeed.length + 4];
        System.arraycopy(buildSeed, 0, ikm, 0, buildSeed.length);
        ikm[buildSeed.length    ] = (byte) (chunkIndex >>> 24);
        ikm[buildSeed.length + 1] = (byte) (chunkIndex >>> 16);
        ikm[buildSeed.length + 2] = (byte) (chunkIndex >>>  8);
        ikm[buildSeed.length + 3] = (byte)  chunkIndex;
        return hkdf(ikm, null, info, NONCE_BYTES);
    }

    /** SHA-256 used both as Info-tied mixing primitive (Spec §6.2) and to
     *  produce integrity hashes (Spec §8.3). */
    public static byte[] sha256(byte[] data) {
        SHA256Digest d = new SHA256Digest();
        d.update(data, 0, data.length);
        byte[] out = new byte[32];
        d.doFinal(out, 0);
        return out;
    }

    /** Domain-separation tag for chunk keys. */
    public static byte[] chunkKeyInfo(String internalName, int blockIndex, String phaseTag) {
        String s = "ruguard/chunk/v1/" + internalName + "/" + blockIndex + "/" + phaseTag;
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Compute SHA-256 integrity hash over all encrypted blocks (Spec §8.3).
     */
    public static byte[] blocksIntegrityHash(byte[][] blocks) {
        if (blocks == null) return new byte[32];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (byte[] b : blocks) {
            if (b != null) {
                bos.write(b, 0, b.length);
            }
        }
        return sha256(bos.toByteArray());
    }
}
