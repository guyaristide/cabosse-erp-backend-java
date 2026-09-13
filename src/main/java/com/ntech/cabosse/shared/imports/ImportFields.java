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

    /** Jour et mois sur un ou deux chiffres, séparés par / . ou tiret. */
    private static final java.util.regex.Pattern DMY_DATE =
            java.util.regex.Pattern.compile("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2}|\\d{4})");

    /** Forme internationale : l'année en tête lève l'ambiguïté jour-mois. */
    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})");

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

    /**
     * La date d'une ligne de carnet, dans les formes qu'un classeur sait
     * produire.
     *
     * <p>Les formats étaient tous stricts sur deux chiffres : « 13/9/2026 »
     * était refusé quand « 13/09/2026 » passait, sur le même carnet et
     * le même jour. Une ligne perdue pour un zéro non tapé, et personne
     * ne pouvait le deviner (relevé le 13/09/2026).</p>
     *
     * <p>On élargit les <strong>formes</strong>, jamais le contenu : rien
     * n'est complété ni supposé. Une date incomplète reste refusée, parce
     * qu'une date inventée fausserait la campagne et le stock. L'ordre
     * reste jour, mois, année, sauf en forme internationale où l'année de
     * quatre chiffres vient en tête et lève l'ambiguïté.</p>
     */
    public static LocalDate parseDate(String raw) {
        String s = clean(raw);
        if (s == null) return null;
        // Une date peut arriver avec son heure : on garde la part date.
        int space = s.indexOf(' ');
        String head = space > 0 ? s.substring(0, space) : s;

        java.util.regex.Matcher iso = ISO_DATE.matcher(head);
        if (iso.matches()) {
            return build(Integer.parseInt(iso.group(3)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(1)));
        }
        java.util.regex.Matcher dmy = DMY_DATE.matcher(head);
        if (dmy.matches()) {
            int year = Integer.parseInt(dmy.group(3));
            // Année sur deux chiffres, telle qu'un classeur peut la garder.
            if (dmy.group(3).length() == 2) year += 2000;
            return build(Integer.parseInt(dmy.group(1)), Integer.parseInt(dmy.group(2)), year);
        }
        // Numéro de série d'un classeur, quand la cellule est restée un
        // nombre à l'export. L'origine est celle d'Excel, pas 1900.
        if (head.matches("\\d{5}")) {
            long serial = Long.parseLong(head);
            if (serial >= 20000 && serial <= 80000) {
                return LocalDate.of(1899, 12, 30).plusDays(serial);
            }
        }
        try { return LocalDate.parse(head, FR_DATE); } catch (Exception ignored) { }
        return null;
    }

    /** Refuse plutôt que de corriger : le 31 février n'est pas le 3 mars. */
    private static LocalDate build(int day, int month, int year) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
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
