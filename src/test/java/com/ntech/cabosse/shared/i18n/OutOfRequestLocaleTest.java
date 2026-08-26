package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La langue d'une opération différée voyage avec elle.
 *
 * <p>Hors requête HTTP, le serveur ne peut pas deviner la langue d'un
 * destinataire : une tâche planifiée, un consommateur d'événement ou une
 * migration n'ont aucune requête sous la main. {@code Messages.current()}
 * y répond français, ce qui est le bon défaut mais une mauvaise réponse
 * pour un message destiné à quelqu'un d'autre.</p>
 *
 * <p>La règle posée le 26/08/2026 : la langue est résolue au moment où
 * l'utilisateur agit, puis transportée avec l'opération (champ de la file
 * d'envoi, champ de l'événement) et rendue explicitement à l'arrivée.</p>
 */
class OutOfRequestLocaleTest {

    @Test
    void a_message_renders_in_the_language_it_is_given() {
        String fr = Messages.msg(Locale.FRENCH, "m.mail-tenant-invitation-subject", "Coopérative Test");
        String en = Messages.msg(Locale.ENGLISH, "m.mail-tenant-invitation-subject", "Coopérative Test");

        assertThat(fr).isEqualTo("Activez votre compte Cabosse ERP : Coopérative Test");
        assertThat(en).isEqualTo("Activate your Cabosse ERP account: Coopérative Test");
    }

    @Test
    void the_default_stays_french_when_nothing_says_otherwise() {
        // Hors contexte de requête, et c'est bien ce test qui s'exécute ici.
        assertThat(Messages.current()).isEqualTo(Locale.FRENCH);
        assertThat(Messages.msg("m.mail-tenant-invitation-subject", "X"))
                .startsWith("Activez");
    }

    @Test
    void a_stored_preference_maps_to_a_served_language() {
        assertThat(Locales.of("en")).isEqualTo(Locale.ENGLISH);
        assertThat(Locales.of("EN")).isEqualTo(Locale.ENGLISH);
        assertThat(Locales.of("en-GB")).isEqualTo(Locale.ENGLISH);
        assertThat(Locales.of("fr")).isEqualTo(Locale.FRENCH);
        // Une préférence absente, vide ou non servie ne fait jamais échouer
        // un envoi : elle retombe sur le français, comme le filtre HTTP.
        assertThat(Locales.of(null)).isEqualTo(Locale.FRENCH);
        assertThat(Locales.of("  ")).isEqualTo(Locale.FRENCH);
        assertThat(Locales.of("es")).isEqualTo(Locale.FRENCH);
    }

    @Test
    void the_person_preference_wins_over_the_organization_one() {
        assertThat(Locales.firstOf("en", "fr")).isEqualTo(Locale.ENGLISH);
        // Personne sans préférence : celle de son organisation s'applique.
        assertThat(Locales.firstOf(null, "en")).isEqualTo(Locale.ENGLISH);
        assertThat(Locales.firstOf("", "en")).isEqualTo(Locale.ENGLISH);
        assertThat(Locales.firstOf(null, null)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void the_tag_stored_on_a_queued_row_is_a_bare_language() {
        assertThat(Locales.tag(Locale.ENGLISH)).isEqualTo("en");
        assertThat(Locales.tag(Locale.FRENCH)).isEqualTo("fr");
        assertThat(Locales.tag(null)).isEqualTo("fr");
    }
}
