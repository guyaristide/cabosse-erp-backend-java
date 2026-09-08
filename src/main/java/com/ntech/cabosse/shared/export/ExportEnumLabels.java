package com.ntech.cabosse.shared.export;

import com.ntech.cabosse.shared.i18n.Messages;

/**
 * Libellés des codes techniques dans les fichiers exportés : un fichier
 * livré à un lecteur parle sa langue, pas celle du modèle (relevé par
 * l'utilisateur le 08/09/2026 sur l'export des avances, qui sortait
 * OPEN et BANK_TRANSFER). Le code brut reste le repli d'une valeur
 * inconnue, plutôt qu'un trou dans le fichier.
 */
public final class ExportEnumLabels {

    private ExportEnumLabels() {}

    public static String paymentMethod(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "CASH" -> Messages.msg("m.pm-cash");
            case "MOBILE_MONEY" -> Messages.msg("m.pm-mobile-money");
            case "CHEQUE" -> Messages.msg("m.pm-cheque");
            case "BANK_TRANSFER" -> Messages.msg("m.pm-bank-transfer");
            case "PRODUCER_CARD" -> Messages.msg("m.pm-producer-card");
            case "OTHER" -> Messages.msg("m.pm-other");
            default -> code;
        };
    }

    /** Statuts d'une avance à un délégué collecteur. */
    public static String advanceStatus(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "PENDING_APPROVAL" -> Messages.msg("m.advst-pending-approval");
            case "APPROVED" -> Messages.msg("m.advst-approved");
            case "REJECTED" -> Messages.msg("m.advst-rejected");
            case "OPEN" -> Messages.msg("m.advst-open");
            case "CLOSED" -> Messages.msg("m.advst-closed");
            default -> code;
        };
    }

    /** Nature d'un engagement producteur : crédit ou avance. */
    public static String creditKind(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "CREDIT" -> Messages.msg("m.crdkind-credit");
            case "ADVANCE" -> Messages.msg("m.crdkind-advance");
            default -> code;
        };
    }

    /** Statuts d'un crédit ou d'une avance à un producteur membre. */
    public static String creditStatus(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "PENDING_APPROVAL" -> Messages.msg("m.advst-pending-approval");
            case "APPROVED" -> Messages.msg("m.advst-approved");
            case "REJECTED" -> Messages.msg("m.advst-rejected");
            case "DISBURSED" -> Messages.msg("m.crdst-disbursed");
            case "SETTLED" -> Messages.msg("m.crdst-settled");
            case "CANCELLED" -> Messages.msg("m.crdst-cancelled");
            default -> code;
        };
    }
}
