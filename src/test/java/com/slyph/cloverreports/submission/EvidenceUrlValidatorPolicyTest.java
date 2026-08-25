package com.slyph.cloverreports.submission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EvidenceUrlValidatorPolicyTest {

    @Test
    void usesSecureTrustedDefaultsWhenConfigurationIsEmpty() {
        assertTrue(EvidenceUrlValidator.isHostAllowed("www.youtube.com", List.of()));
        assertTrue(EvidenceUrlValidator.isHostAllowed("i.imgur.com", List.of()));
        assertFalse(EvidenceUrlValidator.isHostAllowed("evidence.example", List.of()));
    }

    @Test
    void acceptsConfiguredDomainsAndTheirSubdomainsOnly() {
        assertTrue(EvidenceUrlValidator.isHostAllowed("files.example.org", List.of("example.org")));
        assertFalse(EvidenceUrlValidator.isHostAllowed("example.org.attacker.test", List.of("example.org")));
        assertFalse(EvidenceUrlValidator.isHostAllowed("127.0.0.1", List.of("127.0.0.1")));
    }
}
