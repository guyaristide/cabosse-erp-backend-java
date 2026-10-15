package com.ntech.cabosse.shared.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Le montant écrit en toutes lettres, sous le montant en chiffres.
 *
 * <p>Une pièce que l'on signe porte son montant en lettres : c'est ce qui
 * empêche d'ajouter un zéro après coup, et c'est ce que la coopérative
 * attend d'un document qu'elle remet à un délégué (demandé le
 * 14/10/2026).</p>
 *
 * <p>Les centimes sont ignorés : le franc CFA n'en a pas, et les écrire
 * sur une pièce qui n'en porte jamais ajouterait du bruit. Une devise à
 * centimes les verra arrondis au plus proche, ce que le montant en
 * chiffres dit déjà.</p>
 */
public final class AmountInWords {

    private AmountInWords() {}

    private static final String[] FR_UNITS = {
            "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"
    };

    private static final String[] EN_UNITS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
    };

    private static final String[] EN_TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };

    /** Le montant en toutes lettres, dans la langue demandée. */
    public static String of(BigDecimal amount, Locale locale) {
        if (amount == null) return "";
        long value = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        boolean negative = value < 0;
        String words = french(locale) ? fr(Math.abs(value)) : en(Math.abs(value));
        String capitalized = words.isEmpty() ? words
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        if (!negative) return capitalized;
        return (french(locale) ? "moins " : "minus ") + words;
    }

    private static boolean french(Locale locale) {
        return locale == null || "fr".equalsIgnoreCase(locale.getLanguage());
    }

    // ─── Français ───────────────────────────────────────────────────

    private static String fr(long n) {
        if (n < 17) return FR_UNITS[(int) n];
        if (n < 20) return "dix-" + FR_UNITS[(int) (n - 10)];
        if (n < 100) return frTens(n);
        if (n < 1000) return frHundreds(n);
        if (n < 1_000_000) return frScale(n, 1000, "mille", "mille");
        if (n < 1_000_000_000L) return frScale(n, 1_000_000, "million", "millions");
        return frScale(n, 1_000_000_000L, "milliard", "milliards");
    }

    private static String frTens(long n) {
        long tens = n / 10;
        long units = n % 10;
        // Soixante-dix et quatre-vingt-dix se disent par vingtaines : la
        // règle française, pas la belge.
        if (tens == 7 || tens == 9) {
            String base = tens == 7 ? "soixante" : "quatre-vingt";
            long rest = n - (tens == 7 ? 60 : 80);
            // Soixante et onze, mais quatre-vingt-onze : le « et » ne
            // vit que sur la première vingtaine.
            return base + (rest == 11 && tens == 7 ? " et " : "-") + fr(rest);
        }
        String base = switch ((int) tens) {
            case 2 -> "vingt";
            case 3 -> "trente";
            case 4 -> "quarante";
            case 5 -> "cinquante";
            case 6 -> "soixante";
            default -> "quatre-vingt";
        };
        if (units == 0) return tens == 8 ? base + "s" : base;
        if (units == 1 && tens != 8) return base + " et un";
        return base + "-" + FR_UNITS[(int) units];
    }

    private static String frHundreds(long n) {
        long hundreds = n / 100;
        long rest = n % 100;
        String head = hundreds == 1 ? "cent" : fr(hundreds) + " cent";
        if (rest == 0) return hundreds == 1 ? head : head + "s";
        return head + " " + fr(rest);
    }

    private static String frScale(long n, long scale, String singular, String plural) {
        long count = n / scale;
        long rest = n % scale;
        String head;
        if (scale == 1000) {
            // « mille » est invariable, et « un mille » ne se dit pas.
            head = count == 1 ? "mille" : fr(count) + " mille";
        } else {
            head = fr(count) + " " + (count == 1 ? singular : plural);
        }
        return rest == 0 ? head : head + " " + fr(rest);
    }

    // ─── Anglais ────────────────────────────────────────────────────

    private static String en(long n) {
        if (n < 20) return EN_UNITS[(int) n];
        if (n < 100) {
            long tens = n / 10;
            long units = n % 10;
            return EN_TENS[(int) tens] + (units == 0 ? "" : "-" + EN_UNITS[(int) units]);
        }
        if (n < 1000) return enScale(n, 100, "hundred");
        if (n < 1_000_000) return enScale(n, 1000, "thousand");
        if (n < 1_000_000_000L) return enScale(n, 1_000_000, "million");
        return enScale(n, 1_000_000_000L, "billion");
    }

    private static String enScale(long n, long scale, String name) {
        long count = n / scale;
        long rest = n % scale;
        String head = en(count) + " " + name;
        if (rest == 0) return head;
        return head + (scale == 100 ? " and " : " ") + en(rest);
    }
}
