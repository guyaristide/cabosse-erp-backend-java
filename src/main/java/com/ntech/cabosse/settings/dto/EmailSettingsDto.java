package com.ntech.cabosse.settings.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Vue admin des paramètres SMTP. Le mot de passe est toujours renvoyé
 * masqué ({@code ••••••••XXXX}) — jamais en clair, même via cet endpoint.
 *
 * <p>{@code source} indique d'où vient la valeur effective :
 * {@code DATABASE} (config posée par un admin) ou {@code FALLBACK}
 * (lecture {@code application.yml} / variables d'env). Permet à l'UI
 * d'afficher un badge « depuis fichier de config ».</p>
 */
@Schema(description = "Paramètres SMTP : vue admin")
public record EmailSettingsDto(
        String from,
        String host,
        Integer port,
        String username,
        /** Boîte qui reçoit les avis d'assistance. Vide → {@code from}. */
        String supportInbox,
        /** Toujours masqué. Vide si non configuré. */
        String passwordMasked,
        /** {@code true} si un mot de passe est configuré (BD ou YAML). */
        boolean passwordSet,
        /** {@code NONE} / {@code REQUIRED} / {@code OPTIONAL}. */
        String startTls,
        /** {@code true} = mails loggés mais pas envoyés. */
        boolean mockMode,
        /** Source de chaque champ effectif : {@code DATABASE}, {@code FALLBACK}, {@code NONE}. */
        Source source,
        /** Date dernière mise à jour BD ({@code null} si pas encore configuré). */
        String updatedAt,
        String updatedBy
) {

    public enum Source { DATABASE, FALLBACK, NONE }
}
