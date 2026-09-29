package de.joinside.evmap_service.api.auth;

import java.util.Arrays;
import java.util.Optional;

/** The sign-in providers. {@link #token()} is what {@code user_data.provider_identity.provider} stores. */
enum Provider {
    APPLE("apple"), GOOGLE("google"), GITHUB("github");

    private final String token;

    Provider(String token) {
        this.token = token;
    }

    String token() {
        return token;
    }

    static Optional<Provider> fromToken(String token) {
        return Arrays.stream(values()).filter(provider -> provider.token.equals(token)).findFirst();
    }
}
