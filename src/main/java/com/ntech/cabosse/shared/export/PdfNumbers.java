package com.ntech.cabosse.shared.export;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Les nombres d'un document imprimé : groupés par milliers, lisibles
 * d'un coup d'œil.
 *
 * <p>Le format français de la bibliothèque standard sépare les milliers
 * par une espace fine insécable (U+202F), que l'encodage des polices du
 * PDF ne connaît pas : elle disparaît à l'impression, et « 1 806 000 »
 * sort « 1806000 ». C'est ce qu'a vu la coopérative sur ses factures
 * (15/10/2026). L'espace insécable ordinaire (U+00A0), elle, est bien
 * dans l'encodage et s'imprime.</p>
 *
 * <p>Les documents ne portent pas de centimes : le franc CFA n'en a pas,
 * et un poids au gramme ne se lit pas sur un bordereau. Les quantités
 * gardent leurs décimales quand elles en ont, et les perdent quand elles
 * n'en ont pas.</p>
 */
public final class PdfNumbers {

    private PdfNumbers() {}

    /** L'espace insécable que l'encodage des polices PDF sait rendre. */
    private static final char GROUPING = '\u00A0';

    private static DecimalFormat format(int maxFractionDigits) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(GROUPING);
        symbols.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("#,##0", symbols);
        df.setMaximumFractionDigits(maxFractionDigits);
        return df;
    }

    /** Un montant, sans centimes. */
    public static String money(BigDecimal value) {
        return format(0).format(value == null ? BigDecimal.ZERO : value);
    }

    /** Une quantité : ses décimales si elle en a, aucune sinon. */
    public static String quantity(BigDecimal value) {
        return format(2).format(value == null ? BigDecimal.ZERO : value);
    }

    /** Une quantité, ou un tiret quand elle n'est pas renseignée. */
    public static String quantityOrDash(BigDecimal value) {
        return value == null ? "-" : quantity(value);
    }
}
