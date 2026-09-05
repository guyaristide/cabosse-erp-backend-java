package com.ntech.cabosse.shared.i18n;

/**
 * Le libellé d'affichage d'une devise.
 *
 * <p>Un code ISO 4217 ne se montre pas tel quel à qui compte en francs :
 * personne n'écrit « 1 500 000 XOF » sur un talon de chèque, on écrit
 * FCFA. Pour les autres devises, le code fait un libellé honnête tant que
 * le catalogue des devises (CE-60) n'a pas fourni mieux.</p>
 *
 * <p>Règle de la maison, rappelée le 04/09/2026 : la devise ne vit jamais
 * en dur dans une chaîne rendue. Elle vient du tenant, et ce libellé est
 * son seul point de traduction côté serveur.</p>
 */
public final class CurrencyLabels {

    private CurrencyLabels() {}

    public static String display(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) return "FCFA";
        return switch (isoCode.trim().toUpperCase(java.util.Locale.ROOT)) {
            // Les deux francs CFA s'écrivent FCFA dans l'usage, l'ouest
            // comme le centre.
            case "XOF", "XAF" -> "FCFA";
            default -> isoCode.trim().toUpperCase(java.util.Locale.ROOT);
        };
    }
}
