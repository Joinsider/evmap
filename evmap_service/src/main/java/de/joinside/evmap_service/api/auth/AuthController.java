package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.AccessTokenService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
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
        UUID id = users.findOrCreate("apple", apple.subject(request.identityToken()));
        return new AccessTokenResponse(tokens.issue(id));
    }

    record AppleLoginRequest(@NotBlank String identityToken) {
    }

    record AccessTokenResponse(String accessToken) {
    }
}
