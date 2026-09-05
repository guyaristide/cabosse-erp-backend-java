package com.ntech.cabosse.sale.controller;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.shared.export.ExportColumn;
import com.ntech.cabosse.shared.i18n.Messages;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Construit l'export ligne-à-ligne des ventes (format attendu par
 * l'expert métier). Une vente à N lignes produit N rows : les colonnes
 * vente (client, site, canal, paiement, solde, etc.) sont dupliquées,
 * les colonnes ligne (article, quantité, total HT, TVA prorata) sont
 * propres à chaque {@link SaleResponseDto.LineView}.
 *
 * <p>Notes de calcul :</p>
 * <ul>
 *   <li><b>Remise ligne</b> = {@code qty × pu × discountPct / 100}.
 *       Recalculée ici plutôt que stockée car la ligne ne porte que
 *       le total HT après remise.</li>
 *   <li><b>TVA ligne</b> = {@code lineTotalHt × vatRate / 100} —
 *       pro rata du taux de TVA de la vente sur le HT de la ligne.
 *       Cohérent avec le calcul agrégé serveur
 *       ({@code afterDiscount × vatRate}) modulo arrondi.</li>
 *   <li><b>Total TTC ligne</b> = HT ligne + TVA ligne.</li>
 *   <li><b>Total payé</b> et <b>Solde facture</b> restent au niveau
 *       vente (dupliqués sur chaque row) — c'est la convention attendue
 *       par le template.</li>
 * </ul>
 *
 * <p>Le tri est <em>saleDate DESC, ref ASC, ordre des lignes</em>.</p>
 */
final class SaleExportLines {

    private SaleExportLines() {}

    static final DateTimeFormatter CREATED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Row d'export : couple (vente, ligne). Les champs vente sont
     * dupliqués ; le {@code lineOrder} ne sort pas en colonne, il sert
     * uniquement au tri stable.
     */
    record LineRow(SaleResponseDto sale, SaleResponseDto.LineView line, int lineOrder) {}

    static List<LineRow> explode(List<SaleResponseDto> sales) {
        List<LineRow> out = new ArrayList<>();
        if (sales == null) return out;
        // Tri : date DESC, puis ref ASC pour avoir un ordre déterministe.
        sales.stream()
                .sorted(Comparator
                        .comparing(SaleResponseDto::saleDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SaleResponseDto::ref,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(s -> {
                    List<SaleResponseDto.LineView> lines = s.lines();
                    if (lines == null) return;
                    int order = 1;
                    for (SaleResponseDto.LineView l : lines) {
                        out.add(new LineRow(s, l, order++));
                    }
                });
        return out;
    }

    static List<ExportColumn<LineRow>> columns() {
        return List.of(
                ExportColumn.of(Messages.msg("m.imp-h-date-vente"),          r -> r.sale.saleDate()),
                ExportColumn.of(Messages.msg("m.imp-h-designation-article"), r -> r.line.articleName()),
                ExportColumn.of(Messages.msg("m.imp-h-receipt-quantity"),            r -> r.line.quantity()),
                ExportColumn.of(Messages.msg("m.imp-h-commodity-sale-customer"),              r -> r.sale.customerName()),
                ExportColumn.of(Messages.msg("m.imp-h-site"),                r -> r.sale.siteName()),
                ExportColumn.of(Messages.msg("m.imp-h-canal"),               r -> humanChannel(r.sale.channelTypeSnapshot())),
                ExportColumn.of(Messages.msg("m.imp-h-status"),              r -> humanStatus(r.sale.status())),
                ExportColumn.of(Messages.msg("m.imp-h-paiement"),            r -> latestPaymentMethod(r.sale)),
                ExportColumn.of(Messages.msg("m.imp-h-total-ht"),            r -> r.line.lineTotalHt()),
                ExportColumn.of(Messages.msg("m.imp-h-remise"),              SaleExportLines::lineDiscount),
                ExportColumn.of(Messages.msg("m.imp-h-tva"),                 SaleExportLines::lineVat),
                ExportColumn.of(Messages.msg("m.imp-h-total-ttc"),           SaleExportLines::lineTotalTtc),
                ExportColumn.of(Messages.msg("m.imp-h-total-paye"),          r -> r.sale.totalPaid()),
                ExportColumn.of(Messages.msg("m.imp-h-solde-facture"),       r -> r.sale.balanceDue()),
                ExportColumn.of(Messages.msg("m.imp-h-n-facture"),          SaleExportLines::invoiceNumber),
                ExportColumn.of(Messages.msg("m.imp-h-etat-facture"),        r -> invoiceState(r.sale.paymentStatus())),
                ExportColumn.of(Messages.msg("m.imp-h-cree-par"),            r -> r.sale.createdByEmail()),
                ExportColumn.of(Messages.msg("m.imp-h-cree-le"),             r -> formatCreatedAt(r.sale.createdAt()))
        );
    }

    // ─── Calculs ligne ──────────────────────────────────────────────

    /** Remise ligne = qty × pu × discountPct / 100, arrondi 2 décimales. */
    static BigDecimal lineDiscount(LineRow r) {
        BigDecimal qty = nz(r.line.quantity());
        BigDecimal pu = nz(r.line.unitPrice());
        BigDecimal pct = nz(r.line.discountPct());
        if (pct.signum() == 0 || qty.signum() == 0 || pu.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return qty.multiply(pu).multiply(pct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** TVA ligne = HT ligne × vatRate / 100, arrondi 2 décimales. */
    static BigDecimal lineVat(LineRow r) {
        BigDecimal ht = nz(r.line.lineTotalHt());
        BigDecimal rate = nz(r.sale.vatRatePct());
        if (rate.signum() == 0 || ht.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return ht.multiply(rate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** TTC ligne = HT ligne + TVA ligne. */
    static BigDecimal lineTotalTtc(LineRow r) {
        return nz(r.line.lineTotalHt()).add(lineVat(r))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ─── Humanisations ──────────────────────────────────────────────

    /**
     * Canal humanisé. Vide si {@code null} (client jamais classifié) ;
     * pas de fallback « Autre » pour ne pas confondre « non renseigné »
     * et OTHER explicite.
     */
    static String humanChannel(String code) {
        if (code == null || code.isBlank()) return "";
        return switch (code) {
            case "GMS"        -> "GMS";
            case "HOTELLERIE" -> "Hôtellerie";
            case "B2B"        -> "B2B";
            case "B2C"        -> "B2C";
            case "RETAIL"     -> "Retail";
            case "OTHER"      -> "Autre";
            default           -> code;
        };
    }

    static String humanStatus(SaleStatus s) {
        if (s == null) return "";
        return switch (s) {
            case QUOTE     -> "Devis";
            case CONFIRMED -> "Confirmée";
            case DELIVERED -> "Finalisée";
            case CANCELLED -> "Annulée";
        };
    }

    /** État facture : « Soldée » / « Partielle » / « Impayée ». */
    static String invoiceState(PaymentStatus p) {
        if (p == null) return "Impayée";
        return switch (p) {
            case PAID    -> "Soldée";
            case PARTIAL -> "Partielle";
            case UNPAID  -> "Impayée";
        };
    }

    /**
     * Mode du paiement le plus récent (par {@code paidOn} puis
     * {@code recordedAt}). Vide si aucun paiement. CHECK est mentionné
     * dans le mapping cible : on le gère défensivement via switch sur
     * le nom de l'enum pour rester compatible si la valeur est ajoutée
     * plus tard côté {@link PaymentMethod}.
     */
    static String latestPaymentMethod(SaleResponseDto sale) {
        List<SaleResponseDto.PaymentView> payments = sale.payments();
        if (payments == null || payments.isEmpty()) return "";
        SaleResponseDto.PaymentView latest = payments.stream()
                .max(Comparator
                        .comparing(SaleResponseDto.PaymentView::paidOn,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SaleResponseDto.PaymentView::recordedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (latest == null || latest.method() == null) return "";
        return humanPaymentMethod(latest.method().name());
    }

    static String humanPaymentMethod(String code) {
        if (code == null) return "";
        return switch (code) {
            case "CASH"          -> "Comptant";
            case "MOBILE_MONEY"  -> "Mobile Money";
            case "BANK_TRANSFER" -> "Virement";
            case "CHECK"         -> "Chèque";
            case "OTHER"         -> "Autre";
            default              -> code;
        };
    }

    /** Privilégie {@code invoiceNumber} (numéro externe) sinon retombe sur la {@code ref}. */
    static String invoiceNumber(LineRow r) {
        String inv = r.sale.invoiceNumber();
        if (inv != null && !inv.isBlank()) return inv;
        return r.sale.ref();
    }

    static String formatCreatedAt(Instant t) {
        return t == null ? "" : CREATED_AT_FMT.format(t);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Inutile en l'état mais conservé si le binding LocalDate change. */
    @SuppressWarnings("unused")
    private static String fmtDate(LocalDate d) {
        return d == null ? "" : d.toString();
    }
}
