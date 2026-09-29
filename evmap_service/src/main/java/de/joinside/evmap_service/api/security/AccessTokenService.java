package de.joinside.evmap_service.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class AccessTokenService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;
    private final Duration ttl;

    public AccessTokenService(@Value("${evmap.security.jwt-secret}") String secret,
                              @Value("${evmap.security.jwt-ttl}") Duration ttl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String issue(UUID identityId) {
        try {
            String header = encoded("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = encoded("{\"sub\":\"" + identityId + "\",\"iss\":\"evmap\",\"exp\":" + Instant.now().plus(ttl).getEpochSecond() + "}");
            String unsigned = header + "." + payload;
            return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create access token", exception);
        }
    }

    public CurrentUser verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !constantTimeEquals(sign(parts[0] + "." + parts[1]), DECODER.decode(parts[2])))
                throw new IllegalArgumentException();
            String claims = new String(DECODER.decode(parts[1]), StandardCharsets.UTF_8);
            String subject = claim(claims, "sub");
            String issuer = claim(claims, "iss");
            String expiry = claim(claims, "exp");
            if (!"evmap".equals(issuer) || Long.parseLong(expiry) <= Instant.now().getEpochSecond())
                throw new IllegalArgumentException();
            return new CurrentUser(UUID.fromString(subject));
        } catch (Exception _) {
            throw new IllegalArgumentException("Invalid access token");
        }
    }

    /**
     * Reads one flat claim from the payload this service issued itself (signature already checked).
     * A linear scan rather than a regex: the previous {@code .*} patterns backtracked super-linearly.
     * Returns an empty string when absent, which the callers reject like any other bad value.
     */
    private static String claim(String claims, String name) {
        String key = "\"" + name + "\":";
        int start = claims.indexOf(key);
        if (start < 0) return "";
        int from = start + key.length();
        boolean quoted = from < claims.length() && claims.charAt(from) == '"';
        if (quoted) from++;
        int end = from;
        while (end < claims.length() && (quoted ? claims.charAt(end) != '"' : Character.isDigit(claims.charAt(end)))) end++;
        return claims.substring(from, end);
    }

    private String encoded(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sign(String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) return false;
        int diff = 0;
        for (int i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
        return diff == 0;
    }
}
