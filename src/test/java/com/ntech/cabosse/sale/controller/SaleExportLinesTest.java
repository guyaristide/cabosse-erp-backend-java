package com.ntech.cabosse.sale.controller;

import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.dto.SaleResponseDto;
import com.ntech.cabosse.sale.dto.SaleResponseDto.LineView;
import com.ntech.cabosse.sale.dto.SaleResponseDto.PaymentView;
import com.ntech.cabosse.sale.entity.PaymentStatus;
import com.ntech.cabosse.sale.entity.SaleChannel;
import com.ntech.cabosse.sale.entity.SaleStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests purs (sans Quarkus) du builder d'export ligne-à-ligne des
 * ventes. Vérifient les exigences livrées à l'expert métier :
 *
 * <ol>
 *   <li>Une vente à N lignes produit N rows, données vente dupliquées.</li>
 *   <li>Un client sans canal donne une colonne "Canal" vide.</li>
 *   <li>État facture PARTIAL est humanisé en « Partielle ».</li>
 *   <li>Calcul TVA prorata par ligne : 18 % sur 5000 = 900.</li>
 *   <li>Calcul remise ligne, total TTC, mode de paiement le plus récent.</li>
 *   <li>Mapping {@code DELIVERED → "Finalisée"}, {@code MOBILE_MONEY → "Mobile Money"}.</li>
 *   <li>Tri DESC par saleDate.</li>
 *   <li>{@code invoiceNumber} prend le pas sur {@code ref}.</li>
 * </ol>
 */
class SaleExportLinesTest {

    private static SaleResponseDto sale(String ref,
                                         LocalDate saleDate,
                                         String channelTypeSnapshot,
                                         SaleStatus status,
                                         PaymentStatus paymentStatus,
                                         BigDecimal vatRatePct,
                                         BigDecimal totalPaid,
                                         BigDecimal balanceDue,
                                         String invoiceNumber,
                                         List<LineView> lines,
                                         List<PaymentView> payments) {
        return new SaleResponseDto(
                UUID.randomUUID(), ref, UUID.randomUUID(), "Méagui", SaleChannel.B2B,
                UUID.randomUUID(), "client-1", "Pâtisserie Louis", "SARL Louis",
                channelTypeSnapshot,
                saleDate, null, null,
                lines,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, vatRatePct, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                payments, totalPaid, balanceDue, paymentStatus,
                status, null,
                invoiceNumber, null,
                Instant.parse("2026-05-30T10:15:00Z"), Instant.parse("2026-05-30T10:15:00Z"),
                "alice@neiba-technologies.com"
        );
    }

    private static LineView line(String articleName,
                                  BigDecimal quantity,
                                  BigDecimal unitPrice,
                                  BigDecimal discountPct,
                                  BigDecimal lineTotalHt) {
        return new LineView(
                UUID.randomUUID(), UUID.randomUUID(),
                "art-1", articleName, "kg",
                quantity, unitPrice, unitPrice,
                discountPct, lineTotalHt,
                BigDecimal.ZERO, BigDecimal.ZERO, null
        );
    }

    private static PaymentView payment(LocalDate paidOn, PaymentMethod method, BigDecimal amount) {
        return new PaymentView(
                UUID.randomUUID(), paidOn, amount, method,
                null, null, "alice@neiba-technologies.com",
                Instant.parse("2026-05-30T11:00:00Z")
        );
    }

    // ─── Tests ───────────────────────────────────────────────────────

    /** Une vente à 3 lignes → 3 rows, mêmes colonnes vente sur chaque row. */
    @Test
    void shouldExplodeThreeLineSaleIntoThreeRows() {
        SaleResponseDto s = sale("FA-2026-0001", LocalDate.of(2026, 5, 30),
                "B2B", SaleStatus.DELIVERED, PaymentStatus.PAID,
                new BigDecimal("18"),
                new BigDecimal("15000"), BigDecimal.ZERO, "INV-001",
                List.of(
                        line("Tablette 70%", new BigDecimal("10"),
                                new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("5000")),
                        line("Tablette 85%", new BigDecimal("4"),
                                new BigDecimal("750"), BigDecimal.ZERO, new BigDecimal("3000")),
                        line("Cacao en poudre", new BigDecimal("2"),
                                new BigDecimal("3500"), BigDecimal.ZERO, new BigDecimal("7000"))
                ),
                List.of(payment(LocalDate.of(2026, 5, 30),
                        PaymentMethod.MOBILE_MONEY, new BigDecimal("15000"))));

        List<SaleExportLines.LineRow> rows = SaleExportLines.explode(List.of(s));

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.sale().customerName())
                .containsOnly("Pâtisserie Louis");
        assertThat(rows).extracting(r -> r.sale().siteName()).containsOnly("Méagui");
        assertThat(rows).extracting(r -> SaleExportLines.humanChannel(r.sale().channelTypeSnapshot()))
                .containsOnly("B2B");
        assertThat(rows).extracting(r -> r.line().articleName())
                .containsExactly("Tablette 70%", "Tablette 85%", "Cacao en poudre");
    }

    /** Vente sans canal client → colonne « Canal » vide. */
    @Test
    void shouldEmitEmptyChannelWhenSnapshotIsNull() {
        SaleResponseDto s = sale("FA-2026-0002", LocalDate.of(2026, 5, 29),
                null, SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000"), null,
                List.of(line("Savon de Marseille", new BigDecimal("5"),
                        new BigDecimal("2000"), BigDecimal.ZERO, new BigDecimal("10000"))),
                List.of());

        List<SaleExportLines.LineRow> rows = SaleExportLines.explode(List.of(s));

        assertThat(rows).hasSize(1);
        assertThat(SaleExportLines.humanChannel(rows.get(0).sale().channelTypeSnapshot()))
                .isEmpty();
    }

    /** PARTIAL → « Partielle ». */
    @Test
    void shouldHumanizePartialInvoiceState() {
        SaleResponseDto s = sale("FA-2026-0003", LocalDate.of(2026, 5, 28),
                "B2B", SaleStatus.CONFIRMED, PaymentStatus.PARTIAL,
                new BigDecimal("18"),
                new BigDecimal("3000"), new BigDecimal("2000"), null,
                List.of(line("Truffes coffret", new BigDecimal("1"),
                        new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("5000"))),
                List.of(payment(LocalDate.of(2026, 5, 28),
                        PaymentMethod.CASH, new BigDecimal("3000"))));

        assertThat(SaleExportLines.invoiceState(s.paymentStatus())).isEqualTo("Partielle");
    }

    /** Calcul TVA prorata : 18 % sur 5000 = 900. */
    @Test
    void shouldComputeProRataVatPerLine() {
        SaleResponseDto s = sale("FA-2026-0004", LocalDate.of(2026, 5, 27),
                "GMS", SaleStatus.DELIVERED, PaymentStatus.PAID,
                new BigDecimal("18"),
                BigDecimal.ZERO, BigDecimal.ZERO, null,
                List.of(line("Bonbons", new BigDecimal("10"),
                        new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("5000"))),
                List.of());

        List<SaleExportLines.LineRow> rows = SaleExportLines.explode(List.of(s));
        SaleExportLines.LineRow r = rows.get(0);

        assertThat(SaleExportLines.lineVat(r))
                .as("18 %% sur 5000 = 900")
                .isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(SaleExportLines.lineTotalTtc(r))
                .as("HT 5000 + TVA 900 = 5900")
                .isEqualByComparingTo(new BigDecimal("5900.00"));
    }

    /** Remise ligne calculée depuis qty/pu/discountPct. */
    @Test
    void shouldComputeLineDiscountFromRawFactors() {
        // 10 × 500 × 10 % = 500
        LineView l = line("Tablette discountée",
                new BigDecimal("10"), new BigDecimal("500"),
                new BigDecimal("10"), new BigDecimal("4500"));
        SaleResponseDto s = sale("FA-2026-0005", LocalDate.of(2026, 5, 26),
                "B2C", SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4500"), null,
                List.of(l), List.of());

        SaleExportLines.LineRow r = SaleExportLines.explode(List.of(s)).get(0);
        assertThat(SaleExportLines.lineDiscount(r))
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    /** Mode du paiement le plus récent (date la plus tardive). */
    @Test
    void shouldPickLatestPaymentMethod() {
        PaymentView older = payment(LocalDate.of(2026, 5, 1),
                PaymentMethod.CASH, new BigDecimal("1000"));
        PaymentView newer = payment(LocalDate.of(2026, 5, 15),
                PaymentMethod.MOBILE_MONEY, new BigDecimal("2000"));

        SaleResponseDto s = sale("FA-2026-0006", LocalDate.of(2026, 5, 15),
                "B2B", SaleStatus.DELIVERED, PaymentStatus.PAID,
                BigDecimal.ZERO, new BigDecimal("3000"), BigDecimal.ZERO, null,
                List.of(line("Tablette", BigDecimal.ONE, new BigDecimal("3000"),
                        BigDecimal.ZERO, new BigDecimal("3000"))),
                List.of(older, newer));

        assertThat(SaleExportLines.latestPaymentMethod(s)).isEqualTo("Mobile Money");
    }

    /** Mapping DELIVERED → « Finalisée ». */
    @Test
    void shouldMapDeliveredToFinalisee() {
        assertThat(SaleExportLines.humanStatus(SaleStatus.DELIVERED)).isEqualTo("Finalisée");
        assertThat(SaleExportLines.humanStatus(SaleStatus.QUOTE)).isEqualTo("Devis");
        assertThat(SaleExportLines.humanStatus(SaleStatus.CONFIRMED)).isEqualTo("Confirmée");
        assertThat(SaleExportLines.humanStatus(SaleStatus.CANCELLED)).isEqualTo("Annulée");
    }

    /** Mapping canal humanisé. */
    @Test
    void shouldHumanizeAllChannelCodes() {
        assertThat(SaleExportLines.humanChannel("GMS")).isEqualTo("GMS");
        assertThat(SaleExportLines.humanChannel("HOTELLERIE")).isEqualTo("Hôtellerie");
        assertThat(SaleExportLines.humanChannel("B2B")).isEqualTo("B2B");
        assertThat(SaleExportLines.humanChannel("B2C")).isEqualTo("B2C");
        assertThat(SaleExportLines.humanChannel("RETAIL")).isEqualTo("Retail");
        assertThat(SaleExportLines.humanChannel("OTHER")).isEqualTo("Autre");
        assertThat(SaleExportLines.humanChannel(null)).isEmpty();
        assertThat(SaleExportLines.humanChannel("")).isEmpty();
    }

    /** Mapping mode de paiement humanisé (incl. CHECK même si pas dans l'enum actuel). */
    @Test
    void shouldHumanizeAllPaymentMethodCodes() {
        assertThat(SaleExportLines.humanPaymentMethod("CASH")).isEqualTo("Comptant");
        assertThat(SaleExportLines.humanPaymentMethod("MOBILE_MONEY")).isEqualTo("Mobile Money");
        assertThat(SaleExportLines.humanPaymentMethod("BANK_TRANSFER")).isEqualTo("Virement");
        assertThat(SaleExportLines.humanPaymentMethod("CHECK")).isEqualTo("Chèque");
        assertThat(SaleExportLines.humanPaymentMethod("OTHER")).isEqualTo("Autre");
    }

    /** Tri saleDate DESC, ref ASC. */
    @Test
    void shouldSortByDateDescThenRefAsc() {
        SaleResponseDto older = sale("FA-2026-0010", LocalDate.of(2026, 5, 20),
                "B2C", SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                List.of(line("Article A", BigDecimal.ONE, new BigDecimal("100"),
                        BigDecimal.ZERO, new BigDecimal("100"))),
                List.of());
        SaleResponseDto newerA = sale("FA-2026-0020", LocalDate.of(2026, 5, 30),
                "B2C", SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                List.of(line("Article B", BigDecimal.ONE, new BigDecimal("200"),
                        BigDecimal.ZERO, new BigDecimal("200"))),
                List.of());
        SaleResponseDto newerB = sale("FA-2026-0021", LocalDate.of(2026, 5, 30),
                "B2C", SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                List.of(line("Article C", BigDecimal.ONE, new BigDecimal("300"),
                        BigDecimal.ZERO, new BigDecimal("300"))),
                List.of());

        List<SaleExportLines.LineRow> rows = SaleExportLines.explode(List.of(older, newerB, newerA));

        assertThat(rows).extracting(r -> r.sale().ref())
                .containsExactly("FA-2026-0020", "FA-2026-0021", "FA-2026-0010");
    }

    /** invoiceNumber prend le pas sur ref ; fallback ref si invoiceNumber null/blank. */
    @Test
    void shouldPreferInvoiceNumberOverRefWhenPresent() {
        SaleResponseDto withInv = sale("FA-2026-0030", LocalDate.of(2026, 5, 25),
                "B2B", SaleStatus.DELIVERED, PaymentStatus.PAID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "INV-EXTERNE-2026-001",
                List.of(line("Article", BigDecimal.ONE, new BigDecimal("100"),
                        BigDecimal.ZERO, new BigDecimal("100"))),
                List.of());
        SaleResponseDto withoutInv = sale("FA-2026-0031", LocalDate.of(2026, 5, 25),
                "B2B", SaleStatus.DELIVERED, PaymentStatus.PAID,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                List.of(line("Article", BigDecimal.ONE, new BigDecimal("100"),
                        BigDecimal.ZERO, new BigDecimal("100"))),
                List.of());

        List<SaleExportLines.LineRow> rows = SaleExportLines.explode(List.of(withInv, withoutInv));
        // withoutInv et withInv ont même date : tri ref ASC → withInv (0030) avant withoutInv (0031)
        assertThat(SaleExportLines.invoiceNumber(rows.get(0))).isEqualTo("INV-EXTERNE-2026-001");
        assertThat(SaleExportLines.invoiceNumber(rows.get(1))).isEqualTo("FA-2026-0031");
    }

    /** Aucun paiement → cellule « Paiement » vide. */
    @Test
    void shouldReturnEmptyPaymentWhenNoPayments() {
        SaleResponseDto s = sale("FA-2026-0040", LocalDate.of(2026, 5, 24),
                "B2C", SaleStatus.CONFIRMED, PaymentStatus.UNPAID,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500"), null,
                List.of(line("Article", BigDecimal.ONE, new BigDecimal("500"),
                        BigDecimal.ZERO, new BigDecimal("500"))),
                List.of());

        assertThat(SaleExportLines.latestPaymentMethod(s)).isEmpty();
    }
}
