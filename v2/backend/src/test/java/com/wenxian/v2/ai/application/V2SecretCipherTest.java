package com.wenxian.v2.ai.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V2SecretCipherTest {
    @Test
    void encryptsWithRandomIvAndRejectsTheWrongMasterKey() {
        String first = V2SecretCipher.encrypt("sk-player-secret", "master-one");
        String second = V2SecretCipher.encrypt("sk-player-secret", "master-one");

        assertThat(first).isNotEqualTo(second);
        assertThat(V2SecretCipher.decrypt(first, "master-one")).isEqualTo("sk-player-secret");
        assertThatThrownBy(() -> V2SecretCipher.decrypt(first, "master-two"))
                .isInstanceOf(IllegalStateException.class);
    }
}
