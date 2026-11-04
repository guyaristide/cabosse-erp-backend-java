package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.bson.types.Decimal128;

import java.math.BigDecimal;

/**
 * Migration 099 — les trois totaux du décompte de l'acheteur se calculent
 * (VTE-26, règle donnée le 04/11/2026).
 *
 * <p>Valeur totale = facturé TTC + péréquation transport + différentiel
 * ramassage ; total des retenues = BIC + timbre fiscal + commission
 * commerciale + remboursement mandat + remboursement revolving + mise en
 * compte ; montant net = valeur totale − retenues. Les ventes qui portent
 * un décompte reçoivent ces totaux ; celles dont aucune composante n'est
 * renseignée n'en reçoivent pas.</p>
 *
 * <p>{@code runAlways} : le calcul est une fonction des composantes, le
 * rejouer ne change rien à une vente déjà juste.</p>
 */
@ChangeUnit(id = "recompute_commodity_settlement_totals", order = "099", author = "neiba",
        runAlways = true)
public class M099_RecomputeCommoditySettlementTotals {

    private static final String SALES = "commodity_sales";
    static final String[] ADDED = { "transportEqualization", "gatheringDifferential" };
    static final String[] DEDUCTED = { "bic", "fiscalStamp", "salesCommission",
            "mandateRepayment", "revolvingRepayment", "collectorRetention" };

    @Execution
    public void execute(MongoDatabase database) {
        var sales = database.getCollection(SALES);
        for (Document sale : sales.find(Filters.exists("settlement", true))
                .projection(new Document("settlement", 1).append("amountInvoicedTtc", 1))) {
            Document settlement = sale.get("settlement", Document.class);
            if (settlement == null) continue;
            Totals totals = totalsOf(settlement, toDecimal(sale.get("amountInvoicedTtc")));
            var update = totals == null
                    ? Updates.combine(Updates.unset("settlement.totalValue"),
                            Updates.unset("settlement.totalDeductions"), Updates.unset("settlement.netAmount"))
                    : Updates.combine(
                            Updates.set("settlement.totalValue", new Decimal128(totals.totalValue())),
                            Updates.set("settlement.totalDeductions", new Decimal128(totals.totalDeductions())),
                            Updates.set("settlement.netAmount", new Decimal128(totals.netAmount())));
            sales.updateOne(Filters.eq("_id", sale.get("_id")), update);
        }
    }

    record Totals(BigDecimal totalValue, BigDecimal totalDeductions, BigDecimal netAmount) {}

    /** Les totaux d'un décompte stocké, ou {@code null} quand aucune composante n'est renseignée. */
    static Totals totalsOf(Document settlement, BigDecimal invoicedTtc) {
        boolean any = false;
        BigDecimal added = BigDecimal.ZERO;
        for (String k : ADDED) {
            BigDecimal v = toDecimal(settlement.get(k));
            if (v != null) { any = true; added = added.add(v); }
        }
        BigDecimal deducted = BigDecimal.ZERO;
        for (String k : DEDUCTED) {
            BigDecimal v = toDecimal(settlement.get(k));
            if (v != null) { any = true; deducted = deducted.add(v); }
        }
        if (!any) return null;
        BigDecimal total = (invoicedTtc == null ? BigDecimal.ZERO : invoicedTtc).add(added);
        return new Totals(total, deducted, total.subtract(deducted));
    }

    private static BigDecimal toDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Decimal128 d) return d.bigDecimalValue();
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(raw.toString()); } catch (NumberFormatException e) { return null; }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Les totaux calculés remplacent des totaux recopiés : rien à défaire.
    }
}
