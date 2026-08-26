package com.ntech.cabosse.shared.i18n;

import java.util.Locale;

/**
 * Conversion d'une préférence de langue stockée en {@link Locale}.
 *
 * <p>Deux endroits portent cette préférence : {@code UserEntity.locale} et
 * {@code TenantPreferences.language}. Toutes deux sont des chaînes libres
 * validées à l'écriture, mais rien ne garantit ce qu'on relit d'un document
 * ancien ou importé. Un seul point de conversion, tolérant, évite que
 * chaque appelant réinvente sa lecture, et surtout qu'une valeur inconnue
 * fasse échouer un envoi : une langue non servie retombe sur le français,
 * comme le fait déjà le filtre HTTP.</p>
 */
public final class Locales {

    private Locales() {}

    /** Langue servie correspondant à la préférence, français par défaut. */
    public static Locale of(String preference) {
        if (preference == null || preference.isBlank()) return Locale.FRENCH;
        String tag = preference.trim().toLowerCase(Locale.ROOT);
        int separator = tag.indexOf('-');
        if (separator > 0) tag = tag.substring(0, separator);
        return "en".equals(tag) ? Locale.ENGLISH : Locale.FRENCH;
    }

    /**
     * Première préférence renseignée, dans l'ordre donné. Sert à faire
     * primer la langue de la personne sur celle de son organisation.
     */
    public static Locale firstOf(String... preferences) {
        for (String preference : preferences) {
            if (preference != null && !preference.isBlank()) return of(preference);
        }
        return Locale.FRENCH;
    }

    /** Étiquette à stocker dans la file d'envoi, pour tracer ce qui a été rendu. */
    public static String tag(Locale locale) {
        return locale == null ? Locale.FRENCH.getLanguage() : locale.getLanguage();
    }
}
