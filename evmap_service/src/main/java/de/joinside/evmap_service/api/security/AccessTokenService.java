package de.joinside.evmap_service.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
            String subject = claims.replaceAll(".*\\\"sub\\\":\\\"([^\\\"]+)\\\".*", "$1");
            String issuer = claims.replaceAll(".*\\\"iss\\\":\\\"([^\\\"]+)\\\".*", "$1");
            String expiry = claims.replaceAll(".*\\\"exp\\\":([0-9]+).*", "$1");
            if (!"evmap".equals(issuer) || Long.parseLong(expiry) <= Instant.now().getEpochSecond())
                throw new IllegalArgumentException();
            return new CurrentUser(UUID.fromString(subject));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid access token");
        }
    }

    private String encoded(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sign(String value) throws Exception {
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
