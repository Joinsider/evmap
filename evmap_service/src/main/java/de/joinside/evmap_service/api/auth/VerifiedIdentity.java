package de.joinside.evmap_service.api.auth;

/**
 * What a provider has proven about the caller: its stable subject and, if it shared one, an e-mail
 * address together with whether <em>the provider</em> verified it.
 * <p>
 * {@code emailVerified} is the only thing account linking trusts (ADR 0018). Never derive it from
 * anything but the provider's own claim.
 */
record VerifiedIdentity(Provider provider, String subject, String email, boolean emailVerified) {
    private static final String APPLE_RELAY_DOMAIN = "@privaterelay.appleid.com";

    VerifiedIdentity {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Provider returned no subject");
        email = email == null || email.isBlank() ? null : email.trim();
        emailVerified = emailVerified && email != null;
    }

    /**
     * Whether this address may link to, or be linked from, another identity. Apple's private relay
     * addresses are unique per app and user, so they can never legitimately match another provider.
     */
    boolean linkable() {
        return emailVerified && !email.toLowerCase().endsWith(APPLE_RELAY_DOMAIN);
    }
}
