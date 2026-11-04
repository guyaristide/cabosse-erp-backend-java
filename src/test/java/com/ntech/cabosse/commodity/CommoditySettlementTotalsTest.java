package com.ntech.cabosse.commodity;

import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.service.CommoditySaleService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Les totaux du décompte s'additionnent quelles que soient les valeurs
 * (VTE-26). Fabrice avait vu un tiret dans « Total retenues » avec 1 dans
 * chaque retenue, sur la version qui recopiait encore des totaux saisis :
 * ici, 1 partout fait 6, et une seule composante suffit à un décompte.
 */
class CommoditySettlementTotalsTest {

    private static final BigDecimal ONE = BigDecimal.ONE;

    @Test
    void un_franc_dans_chaque_retenue_fait_six() {
        var s = new CommoditySaleEntity.Settlement();
        s.bic = ONE; s.fiscalStamp = ONE; s.salesCommission = ONE;
        s.mandateRepayment = ONE; s.revolvingRepayment = ONE; s.collectorRetention = ONE;

        CommoditySaleService.computeSettlementTotals(s, new BigDecimal("18630000"));

        assertEquals(new BigDecimal("6"), s.totalDeductions);
        assertEquals(new BigDecimal("18630000"), s.totalValue);
        assertEquals(new BigDecimal("18629994"), s.netAmount);
    }

    @Test
    void une_seule_composante_suffit_et_les_absentes_comptent_zero() {
        var s = new CommoditySaleEntity.Settlement();
        s.fiscalStamp = new BigDecimal("5");

        CommoditySaleService.computeSettlementTotals(s, new BigDecimal("100"));

        assertEquals(new BigDecimal("5"), s.totalDeductions);
        assertEquals(new BigDecimal("95"), s.netAmount);
    }

    @Test
    void le_du_est_le_net_du_decompte_sinon_le_facture() {
        var e = new CommoditySaleEntity();
        e.amountInvoicedTtc = new BigDecimal("18630000");
        assertEquals(new BigDecimal("18630000"), e.amountDue());

        e.settlement.transportEqualization = new BigDecimal("350000");
        CommoditySaleService.computeSettlementTotals(e.settlement, e.amountInvoicedTtc);
        assertEquals(new BigDecimal("18980000"), e.amountDue());

        var empty = new CommoditySaleEntity.Settlement();
        CommoditySaleService.computeSettlementTotals(empty, new BigDecimal("1"));
        assertNull(empty.netAmount);
    }
}
