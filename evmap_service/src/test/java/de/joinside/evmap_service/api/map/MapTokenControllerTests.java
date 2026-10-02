package de.joinside.evmap_service.api.map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapTokenControllerTests {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @Test
    @DisplayName("signs an ES256 token MapKit JS accepts: kid, iss, iat, exp, scope and origin, never cached")
    void signsToken() throws Exception {
        KeyPair keys = keyPair();
        MockMvc mvc = mvc(new MapKitProperties("TEAM123456", "KEY1234567", pem(keys), "evmap.joinside.de", Duration.ofMinutes(30)));

        String body = mvc.perform(get("/api/v1/map/token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-02T12:30:00Z"))
                .andReturn().getResponse().getContentAsString();

        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new ECDSAVerifier((ECPublicKey) keys.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEY1234567");
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isEqualTo(Date.from(NOW));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isEqualTo(Date.from(NOW.plus(Duration.ofMinutes(30))));
        assertThat(jwt.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("mapkit_js");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("origin")).isEqualTo("evmap.joinside.de");
    }

    @Test
    @DisplayName("accepts the key with its newlines written as \\n, as an environment variable carries it")
    void escapedNewlines() throws Exception {
        String escaped = pem(keyPair()).replace("\n", "\\n");
        mvc(new MapKitProperties("TEAM123456", "KEY1234567", escaped, "evmap.joinside.de", Duration.ofMinutes(30)))
                .perform(get("/api/v1/map/token")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("without a key the endpoint answers 404, so the web map says it is unavailable")
    void offWithoutKey() throws Exception {
        mvc(new MapKitProperties("TEAM123456", "", "", "evmap.joinside.de", Duration.ofMinutes(30)))
                .perform(get("/api/v1/map/token")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a malformed key fails at startup without echoing the key")
    void malformedKey() {
        assertThatThrownBy(() -> new MapTokenController(
                new MapKitProperties("TEAM123456", "KEY1234567", "not-a-key", "evmap.joinside.de", Duration.ofMinutes(30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAPKIT_PRIVATE_KEY")
                .hasMessageNotContaining("not-a-key");
    }

    private static MockMvc mvc(MapKitProperties properties) {
        return MockMvcBuilders.standaloneSetup(new MapTokenController(properties, Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String pem(KeyPair keys) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
