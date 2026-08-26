package com.ntech.cabosse.shared.i18n;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les courriels partent dans la langue de leur destinataire.
 *
 * <p>Les gabarits n'avaient aucun chemin de traduction : leur texte était
 * écrit dans le HTML, à côté du style en ligne qu'impose la messagerie.
 * Les dupliquer par langue aurait dupliqué cette mise en page ; le texte
 * en a été sorti et vient désormais du catalogue commun.</p>
 *
 * <p>Ce test rend les gabarits pour de vrai, plutôt que de vérifier des
 * clés : c'est le rendu qui part au destinataire, et un placeholder oublié
 * dans un gabarit ne se voit qu'à ce moment-là.</p>
 */
@QuarkusTest
class MailTemplateLocaleTest {

    @Inject
    @Location("mail/tenant-invitation.html")
    Template tenantInvitation;

    @Inject
    @Location("mail/user-invitation.html")
    Template userInvitation;

    @Inject
    @Location("mail/password-reset.html")
    Template passwordReset;

    private String renderTenantInvitation(Locale locale) {
        MailTexts texts = MailTexts.in(locale)
                .put("title", "m.mail-tenant-invitation-title")
                .put("greeting", "m.mail-tenant-invitation-greeting", "Awa")
                .put("provisionedBefore", "m.mail-tenant-invitation-provisioned-before")
                .put("provisionedAfter", "m.mail-tenant-invitation-provisioned-after")
                .put("activateBefore", "m.mail-tenant-invitation-activate-before")
                .put("validity", "m.mail-tenant-invitation-validity")
                .put("activateAfter", "m.mail-tenant-invitation-activate-after")
                .put("cta", "m.mail-tenant-invitation-cta")
                .put("fallbackHint", "m.mail-fallback-hint")
                .put("nextSteps", "m.mail-tenant-invitation-next-steps");
        return tenantInvitation
                .data("tenantName", "Coopérative Test")
                .data("firstName", "Awa")
                .data("activationUrl", "https://example.test/invitation/xyz")
                .data("t", texts.build())
                .render();
    }

    @Test
    void the_tenant_invitation_renders_in_both_languages() {
        String fr = renderTenantInvitation(Locale.FRENCH);
        assertThat(fr).contains("Bienvenue, Awa !", "Activer mon compte", "7 jours");
        assertThat(fr).contains("lang=\"fr\"");

        String en = renderTenantInvitation(Locale.ENGLISH);
        assertThat(en).contains("Welcome, Awa!", "Activate my account", "7 days");
        assertThat(en).contains("lang=\"en\"");
        // La donnée métier ne se traduit pas, elle traverse telle quelle.
        assertThat(en).contains("Coopérative Test", "https://example.test/invitation/xyz");
    }

    @Test
    void no_french_sentence_survives_in_the_english_rendering() {
        String en = renderTenantInvitation(Locale.ENGLISH);
        assertThat(en).doesNotContain("Bienvenue", "Activer mon compte",
                "Si le bouton ne fonctionne pas", "gestion pour unités");
    }

    @Test
    void the_user_invitation_renders_in_both_languages() {
        MailTexts.in(Locale.ENGLISH);
        String en = userInvitation
                .data("firstName", "Awa").data("tenantName", "Coop")
                .data("roleLabel", "Administrator").data("activationUrl", "https://x.test/i/1")
                .data("t", MailTexts.in(Locale.ENGLISH)
                        .put("title", "m.mail-user-invitation-title", "Coop")
                        .put("greeting", "m.mail-user-invitation-greeting", "Awa")
                        .put("invitedBefore", "m.mail-user-invitation-invited-before")
                        .put("invitedMiddle", "m.mail-user-invitation-invited-middle")
                        .put("invitedAfter", "m.mail-user-invitation-invited-after")
                        .put("activateBefore", "m.mail-user-invitation-activate-before")
                        .put("validity", "m.mail-validity-7-days")
                        .put("activateAfter", "m.mail-activate-after")
                        .put("cta", "m.mail-user-invitation-cta")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("notExpected", "m.mail-user-invitation-not-expected")
                        .build())
                .render();
        assertThat(en).contains("Welcome Awa!", "Activate my account", "lang=\"en\"");
        assertThat(en).doesNotContain("Bienvenue", "invité·e");
    }

    @Test
    void the_password_reset_renders_in_both_languages() {
        String en = passwordReset
                .data("firstName", "Awa").data("tenantName", "Coop")
                .data("activationUrl", "https://x.test/i/2")
                .data("t", MailTexts.in(Locale.ENGLISH)
                        .put("title", "m.mail-password-reset-title")
                        .put("heading", "m.mail-password-reset-heading")
                        .put("greeting", "m.mail-password-reset-greeting", "Awa")
                        .put("resetBefore", "m.mail-password-reset-before")
                        .put("resetAfter", "m.mail-password-reset-after")
                        .put("activateBefore", "m.mail-password-reset-activate-before")
                        .put("validity", "m.mail-validity-7-days")
                        .put("activateAfter", "m.mail-activate-after")
                        .put("cta", "m.mail-password-reset-cta")
                        .put("fallbackHint", "m.mail-fallback-hint")
                        .put("notYou", "m.mail-password-reset-not-you")
                        .build())
                .render();
        assertThat(en).contains("Password reset", "Set a new password", "lang=\"en\"");
        assertThat(en).doesNotContain("Bonjour", "mot de passe");
    }
}
