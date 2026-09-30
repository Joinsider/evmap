package de.joinside.evmap_service.api.legal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the privacy policy lives (ADR 0020). It is published by the operator, not shipped in this
 * repository, so the URL is configuration: clients show a link when there is one and none otherwise,
 * and it can change without an app release.
 */
@RestController
class LegalController {
    private final String privacyPolicyUrl;

    LegalController(@Value("${evmap.legal.privacy-policy-url:}") String privacyPolicyUrl) {
        this.privacyPolicyUrl = privacyPolicyUrl == null || privacyPolicyUrl.isBlank() ? null : privacyPolicyUrl.trim();
    }

    @GetMapping("/api/v1/legal")
    LegalResponse legal() {
        return new LegalResponse(privacyPolicyUrl);
    }

    /** {@code privacyPolicyUrl} is left out of the JSON when it is not configured. */
    record LegalResponse(String privacyPolicyUrl) {
    }
}
