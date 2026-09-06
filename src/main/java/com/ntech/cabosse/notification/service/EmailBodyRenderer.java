package com.ntech.cabosse.notification.service;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Habille un courriel de notification du gabarit établi (le layout des
 * invitations et de la réinitialisation de mot de passe).
 *
 * <p>La file stocke et journalise le texte lisible ; l'habillage a lieu
 * au moment de l'envoi, dans le drainer, pour que le journal du plan de
 * contrôle reste du texte et que tous les moteurs de courriel (SMTP,
 * passerelle, repli plateforme) partent du même rendu. Le gabarit
 * échappe le contenu : un corps qui citerait du balisage s'affiche tel
 * quel, il ne s'exécute pas.</p>
 */
@ApplicationScoped
public class EmailBodyRenderer {

    @Inject
    @Location("mail/notification.html")
    Template template;

    public String render(String subject, String body, String localeTag) {
        List<String> lines = body == null ? List.of()
                : Arrays.stream(body.split("\\r?\\n"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        String lang = localeTag == null || localeTag.isBlank() ? "fr" : localeTag;
        return template
                .data("subject", subject == null ? "" : subject)
                .data("lines", lines)
                .data("t", Map.of("lang", lang))
                .render();
    }

    /** Un corps déjà rendu en document HTML ne se ré-habille pas. */
    public static boolean isAlreadyHtml(String body) {
        if (body == null) return false;
        String s = body.stripLeading().toLowerCase();
        return s.startsWith("<!doctype") || s.startsWith("<html");
    }
}
