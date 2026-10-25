package com.ntech.cabosse.shared.api;

import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * La recherche libre d'une liste : un nom de tiers, un libellé, une
 * référence (demandé le 24/10/2026 pour la trésorerie, puis partout où
 * une liste d'opérations se parcourt à l'œil).
 */
public final class TextSearch {
    private TextSearch() {}

    public static boolean blank(String q) { return q == null || q.isBlank(); }

    /** Un filtre Mongo « contient, sans casse » sur l'un des champs ; {@code null} sans texte. */
    public static Bson anyOf(String q, String... fields) {
        if (blank(q)) return null;
        String pattern = Pattern.quote(q.trim());
        List<Bson> ors = new ArrayList<>();
        for (String f : fields) ors.add(Filters.regex(f, pattern, "i"));
        return Filters.or(ors);
    }

    /** Le même « contient, sans casse » en mémoire, sur des valeurs déjà lues. */
    public static boolean matches(String q, String... values) {
        if (blank(q)) return true;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }
}
