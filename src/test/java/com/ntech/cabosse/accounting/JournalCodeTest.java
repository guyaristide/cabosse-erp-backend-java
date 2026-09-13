package com.ntech.cabosse.accounting;

import com.ntech.cabosse.accounting.entity.JournalCode;
import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.service.JournalCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le journal auquel une écriture appartient.
 *
 * <p>Demandé par l'expert-comptable le 12/09/2026 : le fichier des
 * écritures sortait « GEN » pour tout, si bien qu'un cabinet ne pouvait
 * rapprocher aucun de ses journaux.</p>
 */
class JournalCodeTest {

    private static JournalEntry on(String account) {
        JournalEntry entry = new JournalEntry();
        entry.syscohadaAccount = account;
        entry.debit = BigDecimal.TEN;
        return entry;
    }

    @Test
    void a_purchase_without_treasury_goes_to_the_purchases_journal() {
        JournalCode code = JournalCodes.of(PostingSourceType.DIRECT_RECEIPT,
                List.of(on("601000"), on("401000")));
        assertThat(code).isEqualTo(JournalCode.ACH);
    }

    @Test
    void a_sale_without_treasury_goes_to_the_sales_journal() {
        JournalCode code = JournalCodes.of(PostingSourceType.SALE,
                List.of(on("411000"), on("701000")));
        assertThat(code).isEqualTo(JournalCode.VTE);
    }

    /**
     * La trésorerie l'emporte sur la nature : un achat réglé en espèces
     * s'enregistre au journal de caisse, et c'est ce qui permet de
     * rapprocher un journal d'un compte.
     */
    @Test
    void the_treasury_account_decides_before_the_nature_of_the_operation() {
        JournalCode cash = JournalCodes.of(PostingSourceType.DIRECT_RECEIPT,
                List.of(on("601000"), on("571000")));
        assertThat(cash).isEqualTo(JournalCode.CA);

        JournalCode bank = JournalCodes.of(PostingSourceType.SALE_PAYMENT,
                List.of(on("521000"), on("411000")));
        assertThat(bank).isEqualTo(JournalCode.BQ);
    }

    @Test
    void anything_else_falls_to_miscellaneous_operations() {
        assertThat(JournalCodes.of(PostingSourceType.MANUAL_ENTRY,
                List.of(on("601000"), on("471000")))).isEqualTo(JournalCode.OD);
        assertThat(JournalCodes.of(PostingSourceType.OPENING_BALANCE,
                List.of(on("101000"), on("310000")))).isEqualTo(JournalCode.OD);
    }

    /** Une pièce sans ligne ni nature ne fait pas tomber la lecture. */
    @Test
    void an_empty_piece_still_names_a_journal() {
        assertThat(JournalCodes.of(null, null)).isEqualTo(JournalCode.OD);
        assertThat(JournalCodes.of(PostingSourceType.MANUAL_ENTRY, List.of()))
                .isEqualTo(JournalCode.OD);
    }
}
