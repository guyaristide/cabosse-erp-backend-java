package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.JournalCode;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.PostingSourceType;

import java.util.List;

/**
 * À quel journal une écriture appartient.
 *
 * <p>La trésorerie l'emporte sur la nature : un achat réglé en espèces
 * s'enregistre au journal de caisse, c'est la pratique et c'est ce qui
 * permet de rapprocher un journal d'un compte. À défaut de compte de
 * trésorerie, la nature de l'opération décide.</p>
 */
public final class JournalCodes {

    private JournalCodes() {
    }

    /** Banque : 52x. Caisse : 57x. Le reste ne tranche pas. */
    private static final String BANK_PREFIX = "52";
    private static final String CASH_PREFIX = "57";

    public static JournalCode of(PostingSourceType sourceType, List<JournalEntry> entries) {
        JournalCode byTreasury = fromAccounts(entries);
        if (byTreasury != null) return byTreasury;
        return fromSource(sourceType);
    }

    private static JournalCode fromAccounts(List<JournalEntry> entries) {
        if (entries == null) return null;
        for (JournalEntry entry : entries) {
            String account = entry.syscohadaAccount;
            if (account == null) continue;
            if (account.startsWith(BANK_PREFIX)) return JournalCode.BQ;
            if (account.startsWith(CASH_PREFIX)) return JournalCode.CA;
        }
        return null;
    }

    private static JournalCode fromSource(PostingSourceType sourceType) {
        if (sourceType == null) return JournalCode.OD;
        return switch (sourceType) {
            case PURCHASE_ORDER, PURCHASE_ORDER_REVERSAL,
                 DIRECT_RECEIPT, DIRECT_RECEIPT_REVERSAL,
                 PRODUCER_PURCHASE, PRODUCER_PURCHASE_REVERSAL,
                 PRODUCER_PURCHASE_SETTLEMENT, PRODUCER_PURCHASE_SETTLEMENT_REVERSAL,
                 COLLECTOR_DELIVERY,
                 DIRECT_EXPENSE, DIRECT_EXPENSE_REVERSAL -> JournalCode.ACH;
            case SALE, SALE_REVERSAL, COMMODITY_SALE -> JournalCode.VTE;
            default -> JournalCode.OD;
        };
    }
}
