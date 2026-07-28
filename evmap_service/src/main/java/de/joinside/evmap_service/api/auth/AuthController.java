package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.AccessTokenService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppleIdentityTokenVerifier apple;
    private final UserIdentityService users;
    private final AccessTokenService tokens;

    AuthController(AppleIdentityTokenVerifier apple, UserIdentityService users, AccessTokenService tokens) {
        this.apple = apple;
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/apple")
    AccessTokenResponse apple(@RequestBody AppleLoginRequest request) {
        log.debug("Apple sign-in requested");
        UUID id = users.findOrCreate("apple", apple.subject(request.identityToken()));
        log.info("Issued access token for identity {}", id);
        return new AccessTokenResponse(tokens.issue(id));
    }

    record AppleLoginRequest(@NotBlank String identityToken) {
    }

    record AccessTokenResponse(String accessToken) {
    }
}
