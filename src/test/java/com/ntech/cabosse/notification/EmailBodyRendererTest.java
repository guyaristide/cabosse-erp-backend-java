package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.service.EmailBodyRenderer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les courriels de notification partent dans le gabarit établi.
 *
 * <p>Relevé par l'utilisateur le 06/09/2026 : les alertes de la file
 * (avance en attente, décaissement à préparer) partaient en texte nu,
 * hors de la mise en page des invitations et de la réinitialisation de
 * mot de passe. L'habillage a lieu à l'envoi, le journal garde le texte
 * lisible.</p>
 */
@QuarkusTest
class EmailBodyRendererTest {

    @Inject EmailBodyRenderer renderer;

    @Test
    void the_notification_wears_the_established_layout() {
        String html = renderer.render(
                "Avance AVC-2026-0001 en attente",
                "Kouassi a demandé 500 000.\nLa demande attend une décision.",
                "fr");

        // Le layout de base : marque, carte, pied de page.
        assertThat(html).contains("Cabosse");
        assertThat(html).contains("<html");
        assertThat(html).contains("lang=\"fr\"");
        // Le sujet en titre, le corps en paragraphes distincts.
        assertThat(html).contains("Avance AVC-2026-0001 en attente");
        assertThat(html).contains("Kouassi a demandé 500 000.");
        assertThat(html).contains("La demande attend une décision.");
    }

    @Test
    void the_body_text_is_escaped_not_executed() {
        String html = renderer.render("Sujet", "Un <script>alert('x')</script> cité", "fr");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void an_already_rendered_document_is_left_alone() {
        assertThat(EmailBodyRenderer.isAlreadyHtml("<!DOCTYPE html><html></html>")).isTrue();
        assertThat(EmailBodyRenderer.isAlreadyHtml("  <html lang=\"fr\">")).isTrue();
        assertThat(EmailBodyRenderer.isAlreadyHtml("Bonjour, une avance attend.")).isFalse();
        assertThat(EmailBodyRenderer.isAlreadyHtml(null)).isFalse();
    }
}
