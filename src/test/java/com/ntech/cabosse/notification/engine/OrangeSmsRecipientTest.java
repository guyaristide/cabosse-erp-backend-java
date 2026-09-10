package com.ntech.cabosse.notification.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le destinataire d'un SMS Orange doit être strictement numérique :
 * l'opérateur rejette les espaces et les caractères parasites (« Only
 * numeric phone number is accepted »). L'émetteur, lui, reste libre :
 * une marque alphanumérique déclarée au contrat passe telle quelle.
 */
class OrangeSmsRecipientTest {

    @Test
    void the_recipient_is_rebuilt_as_plus_and_digits_only() {
        assertThat(OrangeSmsEngine.recipientTel("+225 07 10 84 88 68"))
                .isEqualTo("tel:+2250710848868");
        assertThat(OrangeSmsEngine.recipientTel("(+225) 0154-53-66-88"))
                .isEqualTo("tel:+22501545366" + "88");
        assertThat(OrangeSmsEngine.recipientTel("tel:+2250172757084"))
                .isEqualTo("tel:+2250172757084");
    }

    @Test
    void an_unusable_number_is_refused_instead_of_sent_broken() {
        assertThat(OrangeSmsEngine.recipientTel(null)).isNull();
        assertThat(OrangeSmsEngine.recipientTel("   ")).isNull();
        assertThat(OrangeSmsEngine.recipientTel("12345")).isNull();
    }
}
