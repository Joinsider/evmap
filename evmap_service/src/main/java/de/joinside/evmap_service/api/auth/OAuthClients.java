package de.joinside.evmap_service.api.auth;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The HTTP client every provider exchange uses: short timeouts, since a user is waiting on it. */
final class OAuthClients {
    private OAuthClients() {
    }

    static RestClient build(RestClient.Builder builder, AuthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return builder.requestFactory(requestFactory).build();
    }
}
