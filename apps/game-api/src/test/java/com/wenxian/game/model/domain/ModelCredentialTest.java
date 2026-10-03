package com.wenxian.game.model.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCredentialTest {
    @Test
    void neverPrintsRawSecretAndFingerprintChangesWithSecret() {
        ModelCredential first = ModelCredential.user("sk-sensitive-value-one");
        ModelCredential second = ModelCredential.user("sk-sensitive-value-two");

        assertThat(first.toString()).doesNotContain("sk-sensitive-value-one");
        assertThat(first.fingerprint()).hasSize(64).isNotEqualTo(second.fingerprint());
        assertThat(first.source()).isEqualTo(CredentialSource.USER);
    }
}
