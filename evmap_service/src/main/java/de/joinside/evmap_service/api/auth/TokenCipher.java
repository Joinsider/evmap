package de.joinside.evmap_service.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the provider refresh tokens kept for revocation (ADR 0020): AES-256-GCM with a random
 * nonce per value, key from {@code TOKEN_ENCRYPTION_KEY} (base64, 32 bytes). Without a key nothing is
 * stored — {@link #enabled()} is false — so a local stack works without one. A key that is set but not
 * a valid AES-256 key stops the application at startup instead of quietly storing nothing.
 * <p>
 * The stored form is {@code v1:} plus base64 of nonce and ciphertext, leaving room to rotate the scheme.
 */
@Component
class TokenCipher {
    private static final String VERSION = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    TokenCipher(@Value("${evmap.security.token-encryption-key:}") String base64Key) {
        this.key = base64Key == null || base64Key.isBlank() ? null : parse(base64Key.trim());
    }

    private static SecretKeySpec parse(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException _) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is not valid base64");
        }
        if (bytes.length != KEY_BYTES) throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
        return new SecretKeySpec(bytes, "AES");
    }

    boolean enabled() {
        return key != null;
    }

    String encrypt(String plain) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] joined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, joined, 0, nonce.length);
            System.arraycopy(encrypted, 0, joined, nonce.length, encrypted.length);
            return VERSION + Base64.getEncoder().encodeToString(joined);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt a token (" + exception.getClass().getSimpleName() + ")");
        }
    }

    /** @throws IllegalStateException when the value was not written with this key, or was altered */
    String decrypt(String stored) {
        requireKey();
        if (!stored.startsWith(VERSION)) throw new IllegalStateException("Unknown token encryption version");
        try {
            byte[] joined = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, joined, 0, NONCE_BYTES));
            return new String(cipher.doFinal(joined, NONCE_BYTES, joined.length - NONCE_BYTES), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not decrypt a token (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("No token encryption key configured");
    }
}
