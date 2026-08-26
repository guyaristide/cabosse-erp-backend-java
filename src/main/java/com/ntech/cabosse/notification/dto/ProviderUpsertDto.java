package com.ntech.cabosse.notification.dto;

import com.ntech.cabosse.notification.entity.NotificationUsage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Création ou modification d'une passerelle.
 *
 * <p>Secrets en écriture seule : une valeur absente ou vide <strong>ne
 * remplace pas</strong> celle déjà enregistrée. C'est ce qui permet à
 * l'écran d'administration de renvoyer le formulaire tel qu'il l'a reçu,
 * masques compris, sans écraser un vrai secret par des points. Pour
 * effacer une valeur, envoyer la sentinelle {@code <<clear>>}.</p>
 *
 * <p>Les usages sont fournis en liste complète : les rangs sont réécrits
 * en bloc, jamais permutés deux à deux.</p>
 */
@Schema(description = "Passerelle d'envoi à créer ou modifier")
public record ProviderUpsertDto(
        @NotBlank(message = "{v.moteur-requis}")
        String engineCode,

        @NotBlank(message = "{v.libelle-requis-2}")
        String label,

        boolean active,

        @Schema(description = "Valeurs des paramètres déclarés par le moteur.")
        Map<String, String> params,

        @Schema(description = "Usages servis et rangs de préférence (0 = essayé en premier).")
        List<UsageDto> usages
) {
    @Schema(description = "Usage servi et rang de préférence")
    public record UsageDto(NotificationUsage usage, @PositiveOrZero int priority) {}
}
