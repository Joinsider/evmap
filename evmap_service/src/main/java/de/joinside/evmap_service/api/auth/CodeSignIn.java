package de.joinside.evmap_service.api.auth;

import java.util.Map;

/**
 * One provider's authorization-code flow, exchanged in the backend (ADR 0018). The client opens
 * {@link #authorization()} with its own {@code state} and, if {@link Authorization#pkce()}, a S256
 * {@code code_challenge}; the provider redirects back with a code, which the client posts here.
 * The client secret never leaves the backend, and GitHub — which has no OpenID Connect for users —
 * fits the same shape.
 */
interface CodeSignIn {
    Provider provider();

    boolean enabled();

    Authorization authorization();

    /**
     * Redeems {@code code} and returns what the provider vouches for.
     *
     * @throws SignInFailedException when the provider rejects the code or its answer is unusable
     */
    VerifiedIdentity exchange(String code, String codeVerifier);

    /**
     * What a client needs to start the flow. {@code parameters} are appended to
     * {@code authorizationEndpoint} as they are; the client adds {@code state} and the PKCE challenge.
     */
    record Authorization(String provider, String authorizationEndpoint, Map<String, String> parameters, boolean pkce) {
    }
}
