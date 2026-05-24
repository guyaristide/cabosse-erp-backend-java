package com.ntech.cabosse.shared.imports;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
// (used by listSink helper below)

/**
 * Parsers FR-permissifs partagés par tous les imports en masse (articles,
 * fournisseurs, clients, sites, types de dépense…). Centralisés ici pour
 * que les conventions (Oui/Non, format nombre, normalisation accents)
 * soient identiques d'un import à l'autre.
 */
public final class ImportParsers {

    private ImportParsers() {}

    /**
     * Parse un booléen "Oui/Non" / "true/false" / "1/0". {@code null} si
     * vide. Erreur si valeur inconnue — l'erreur est ajoutée à
     * {@code issues}.
     */
    /**
     * Forme minimale d'une remontée d'erreur attendue par les parsers.
     * Chaque entité expose son propre type avec la même forme via son
     * DTO Preview ; les services convertissent via {@link #lift}.
     */
    public interface IssueSink {
        void add(String field, String message);
    }

    public static Boolean parseBoolean(String raw, IssueSink issues, String field) {
        if (raw == null || raw.isBlank()) return null;
        String n = normalize(raw);
        return switch (n) {
            case "oui", "yes", "y", "true", "vrai", "1" -> Boolean.TRUE;
            case "non", "no", "n", "false", "faux", "0" -> Boolean.FALSE;
            default -> {
                issues.add(field, "Valeur « " + raw + " » invalide (attendu : Oui ou Non).");
                yield null;
            }
        };
    }

    /**
     * Parse un nombre acceptant le format FR ({@code 1 800,50} avec
     * espaces fines insécables) ou US ({@code 1800.50}). {@code null} si
     * vide.
     */
    public static BigDecimal parseDecimal(String raw, IssueSink issues, String field) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw
                .replace(" ", "")     // NBSP
                .replace(" ", "")     // narrow NBSP
                .replace(" ", "")
                .replace(",", ".");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            issues.add(field, "Nombre invalide : « " + raw + " ».");
            return null;
        }
    }

    /** Trim + {@code null} si la chaîne résultante est vide. */
    public static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Normalise pour comparaison : minuscules, sans accents, sans
     * tirets/sousligns, espaces compactés. Utilisé par les parseurs de
     * libellés (Type, Catégorie, etc.).
     */
    public static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return n.replace("_", " ").replace("-", " ").replaceAll("\\s+", " ");
    }

    /** Slug en minuscules + tirets, pour générer un code depuis un nom. */
    public static String slugify(String name) {
        if (name == null) return null;
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? null : n;
    }

    /**
     * Adapter d'{@link IssueSink} qui pousse les issues dans une liste
     * de records typés via une {@code BiFunction}. Pratique pour
     * brancher les parsers sur le {@code FieldIssue} d'une entité.
     */
    public static <T> IssueSink listSink(List<T> target,
                                         java.util.function.BiFunction<String, String, T> ctor) {
        return (field, message) -> target.add(ctor.apply(field, message));
    }

    /** No-op pour les cas où on ne veut pas remonter les issues. */
    public static IssueSink lift() {
        return (field, message) -> {};
    }
}
