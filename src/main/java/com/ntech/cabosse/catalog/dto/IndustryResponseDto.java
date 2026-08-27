package com.ntech.cabosse.catalog.dto;

import com.ntech.cabosse.catalog.entity.IndustryEntity;
import com.ntech.cabosse.shared.i18n.Messages;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Locale;

/**
 * Activité / filière telle que la lit un client.
 *
 * <p>Le libellé part dans la langue de la requête. Il ne se traduit pas
 * par une clé de catalogue : une filière est une donnée que la plateforme
 * saisit, pas une valeur d'énumération. L'entité porte donc un champ par
 * langue, et la lecture retombe sur le français quand l'anglais n'a pas
 * été renseigné, pour qu'une filière ancienne reste lisible.</p>
 */
@Schema(description = "Activité / filière : catalogue strict éditable par la plateforme")
public record IndustryResponseDto(
        String code,
        String label,
        String description
) {

    public static IndustryResponseDto from(IndustryEntity e, Locale locale) {
        boolean english = locale != null && Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
        return new IndustryResponseDto(
                e.code,
                english ? orFallback(e.labelEn, e.label) : e.label,
                english ? orFallback(e.descriptionEn, e.description) : e.description);
    }

    /** Variante qui lit la langue de la requête en cours. */
    public static IndustryResponseDto from(IndustryEntity e) {
        return from(e, Messages.current());
    }

    private static String orFallback(String translated, String fallback) {
        return translated == null || translated.isBlank() ? fallback : translated;
    }
}
