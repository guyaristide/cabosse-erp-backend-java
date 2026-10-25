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

    /** Nature d'un mouvement de stock. */
    public static String movementKind(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "IN" -> Messages.msg("m.mvk-in");
            case "OUT" -> Messages.msg("m.mvk-out");
            case "ADJUSTMENT" -> Messages.msg("m.mvk-adjustment");
            case "OPENING" -> Messages.msg("m.mvk-opening");
            case "TRANSFER_OUT" -> Messages.msg("m.mvk-transfer-out");
            case "TRANSFER_IN" -> Messages.msg("m.mvk-transfer-in");
            default -> code;
        };
    }

    /**
     * D'où vient un mouvement de stock.
     *
     * <p>L'export du journal rendait le code brut : un lecteur y voyait
     * {@code PRODUCER_PURCHASE} au lieu d'un achat au producteur
     * (12/10/2026).</p>
     */
    public static String movementSource(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "OPENING" -> Messages.msg("m.mvs-opening");
            case "PURCHASE_ORDER" -> Messages.msg("m.mvs-purchase-order");
            case "DIRECT_RECEIPT" -> Messages.msg("m.mvs-direct-receipt");
            case "PRODUCTION" -> Messages.msg("m.mvs-production");
            case "SALE" -> Messages.msg("m.mvs-sale");
            case "TRANSFER" -> Messages.msg("m.mvs-transfer");
            case "INVENTORY" -> Messages.msg("m.mvs-inventory");
            case "MANUAL" -> Messages.msg("m.mvs-manual");
            case "RECLASSIFICATION" -> Messages.msg("m.mvs-reclassification");
            case "AGRICULTURAL_QC" -> Messages.msg("m.mvs-agricultural-qc");
            case "COLLECTOR_DELIVERY" -> Messages.msg("m.mvs-collector-delivery");
            case "PRODUCER_PURCHASE" -> Messages.msg("m.mvs-producer-purchase");
            case "COMMODITY_SALE" -> Messages.msg("m.mvs-commodity-sale");
            case "DISPATCH_NOTE" -> Messages.msg("m.mvs-dispatch-note");
            case "STOCK_CORRECTION" -> Messages.msg("m.mvs-stock-correction");
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
    /** Nature d'un engagement à payer, telle que la file la nomme. */
    public static String payableKind(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "COLLECTOR_ADVANCE" -> Messages.msg("m.paykind-collector-advance");
            case "COLLECTOR_LOAN" -> Messages.msg("m.paykind-collector-loan");
            case "MEMBER_CREDIT" -> Messages.msg("m.paykind-member-credit");
            case "SUPPLIER_RECEIPT" -> Messages.msg("m.paykind-supplier-receipt");
            case "PRODUCER_PURCHASE" -> Messages.msg("m.paykind-producer-purchase");
            case "DELEGATE_PURCHASE" -> Messages.msg("m.paykind-delegate-purchase");
            case "SUPPLIER_PURCHASE" -> Messages.msg("m.paykind-supplier-purchase");
            case "SUBSCRIPTION" -> Messages.msg("m.paykind-subscription");
            case "PETTY_CASH" -> Messages.msg("m.paykind-petty-cash");
            default -> code;
        };
    }

    /** Avance de collecte ou prêt à un délégué. */
    public static String advanceKind(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "COLLECTION" -> Messages.msg("m.advkind-collection");
            case "LOAN" -> Messages.msg("m.advkind-loan");
            default -> code;
        };
    }

    /** Nature d'une créance à encaisser. */
    public static String receivableKind(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "SALE" -> Messages.msg("m.rcvkind-sale");
            case "COMMODITY_SALE" -> Messages.msg("m.rcvkind-commodity-sale");
            default -> code;
        };
    }

    /** Où en est une dépense directe. */
    public static String expenseStatus(String code) {
        if (code == null || code.isBlank()) return code;
        return switch (code) {
            case "TO_APPROVE" -> Messages.msg("m.expst-to-approve");
            case "TO_PAY" -> Messages.msg("m.expst-to-pay");
            case "PARTIALLY_PAID" -> Messages.msg("m.expst-partially-paid");
            case "SETTLED" -> Messages.msg("m.expst-settled");
            case "REJECTED" -> Messages.msg("m.expst-rejected");
            default -> code;
        };
    }
}
