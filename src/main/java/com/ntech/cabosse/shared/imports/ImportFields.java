package com.ntech.cabosse.shared.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Normalisation des champs des fichiers importés des carnets papier.
 * Les extraits copiés du web charrient l'espace insécable (160) et la
 * fine (8239) : elles se traitent comme l'espace ordinaire, partout.
 */
public final class ImportFields {

    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ImportFields() {}

    public static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replace('\u00A0', ' ').replace('\u202F', ' ')
                .replaceAll("[\\s\\u00A0\\u202F]+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    public static String normalize(String raw) {
        String s = clean(raw);
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static LocalDate parseDate(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        try { return LocalDate.parse(s, FR_DATE); } catch (Exception ignored) { }
        try {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        } catch (Exception ignored) { }
        try {
            // Année sur deux chiffres, telle qu'un classeur peut la garder.
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yy"));
        } catch (Exception ignored) { }
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (Exception ignored) { }
        return null;
    }

    public static BigDecimal parseDecimal(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        s = s.replace(" ", "");
        // Le carnet écrit « 10,936 » pour 10 936 : des groupes de trois
        // chiffres après chaque virgule sont des milliers, pas une
        // décimale. « 0,936 » ou « 12,5 » restent des décimales.
        if (s.matches("[1-9]\\d{0,2}(,\\d{3})+")) {
            s = s.replace(",", "");
        }
        try {
            return new BigDecimal(s.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Integer parseInt(String raw) {
        BigDecimal d = parseDecimal(raw);
        return d == null ? null : d.intValue();
    }

    /** Les 8 derniers chiffres d'un téléphone, comme le contrôle doublons. */
    public static String phoneKey(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8) return digits.isEmpty() ? null : digits;
        return digits.substring(digits.length() - 8);
    }
}
