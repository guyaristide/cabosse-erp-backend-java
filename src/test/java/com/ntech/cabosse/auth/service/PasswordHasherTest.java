package com.ntech.cabosse.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test unitaire pur — pas de @QuarkusTest, pas de Mongo, pas d'HTTP.
 * Template à dupliquer pour {@code FileUploadLimitsTest},
 * {@code InvitationTokenServiceTest}, etc.
 *
 * <p>Sert aussi de garde-fou contre une mauvaise configuration de
 * {@link PasswordHasher} (log_rounds trop bas, algo changé sans
 * intention).</p>
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void shouldHashAndVerifyMatchingPassword() {
        String hash = hasher.hash("S3cret!");
        assertThat(hasher.verify("S3cret!", hash)).isTrue();
    }

    @Test
    void shouldRejectWrongPassword() {
        String hash = hasher.hash("S3cret!");
        assertThat(hasher.verify("wrong", hash)).isFalse();
    }

    @Test
    void shouldRejectNullInputs() {
        String hash = hasher.hash("S3cret!");
        assertThat(hasher.verify(null, hash)).isFalse();
        assertThat(hasher.verify("S3cret!", null)).isFalse();
    }

    @Test
    void shouldRejectMalformedHash() {
        assertThat(hasher.verify("anything", "not-a-bcrypt-hash")).isFalse();
        assertThat(hasher.verify("anything", "")).isFalse();
    }

    @Test
    void shouldRefuseToHashEmptyPassword() {
        assertThatThrownBy(() -> hasher.hash(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProduceDifferentHashesForSamePassword() {
        // BCrypt randomise le salt → 2 hashes différents pour la même entrée.
        // Sans ce comportement, un dump de base permettrait l'identification
        // des comptes avec mots de passe communs.
        String h1 = hasher.hash("S3cret!");
        String h2 = hasher.hash("S3cret!");
        assertThat(h1).isNotEqualTo(h2);
        // Mais les deux vérifient le même password
        assertThat(hasher.verify("S3cret!", h1)).isTrue();
        assertThat(hasher.verify("S3cret!", h2)).isTrue();
    }
}
