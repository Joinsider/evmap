package de.joinside.evmap_service.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTests {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    @Test
    @DisplayName("round-trips a value and never stores it in the clear")
    void roundTrip() {
        TokenCipher cipher = new TokenCipher(KEY);

        String stored = cipher.encrypt("refresh-token-value");

        assertThat(stored).startsWith("v1:").doesNotContain("refresh-token-value");
        assertThat(cipher.decrypt(stored)).isEqualTo("refresh-token-value");
    }

    @Test
    @DisplayName("uses a fresh nonce for every value")
    void freshNonce() {
        TokenCipher cipher = new TokenCipher(KEY);

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("refuses a value written with another key or altered afterwards")
    void rejectsForeignAndTamperedValues() {
        String stored = new TokenCipher(KEY).encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(stored.substring(3));
        bytes[bytes.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> new TokenCipher(OTHER_KEY).decrypt(stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(KEY).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TokenCipher(KEY).decrypt("v0:AAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("without a key it is off and encrypting is an error")
    void disabledWithoutKey() {
        TokenCipher cipher = new TokenCipher("");

        assertThat(cipher.enabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a key that is set but unusable stops the application without echoing it")
    void rejectsBadKeys() {
        assertThatThrownBy(() -> new TokenCipher("not base64!")).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not base64!");
        assertThatThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
    }
}
