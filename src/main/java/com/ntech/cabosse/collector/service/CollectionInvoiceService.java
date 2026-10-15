package com.ntech.cabosse.collector.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ntech.cabosse.collector.dto.DelegateLedgerDto;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.export.AmountInWords;
import com.ntech.cabosse.shared.export.PdfBranding;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * La facture de collecte d'une livraison de délégué (demandé le
 * 15/10/2026).
 *
 * <p>La coopérative remet au délégué, à chaque livraison, le décompte de
 * ce qu'elle lui achète et de ce qu'elle retient : le poids livré reçu
 * par reçu, sa rémunération, les avances que la livraison apure, la mise
 * en compte, et ce qui reste à lui verser. Le compte courant encadre le
 * tout, avant et après.</p>
 *
 * <p>Le modèle du terrain prévoyait des déductions diverses, engrais ou
 * frais de route : elles n'existent pas encore dans le produit, et
 * imprimer un cadre vide ferait croire qu'on les tient. La facture n'en
 * porte donc pas.</p>
 */
@ApplicationScoped
public class CollectionInvoiceService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color HEADER_BG = new Color(0xF0, 0xF0, 0xF0);

    @Inject ProducerPurchaseRepository purchases;
    @Inject CollectorAdvanceRepository advances;
    @Inject SupplierRepository suppliers;
    @Inject DelegateAccountService accounts;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public byte[] build(UUID delegateSupplierId, String deliveryRef, UUID campaignId) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId).orElseThrow(
                () -> new NotFoundException(
                        Messages.msg("m.col-delegate-not-found", delegateSupplierId)));

        List<ProducerPurchaseEntity> lines = purchases.listByDelegate(delegateSupplierId, campaignId)
                .stream()
                .filter(r -> deliveryRef.equals(r.deliveryRef) || deliveryRef.equals(r.ref))
                .toList();
        if (lines.isEmpty()) {
            throw new NotFoundException(Messages.msg("m.col-invoice-note-not-found", deliveryRef));
        }

        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        Locale locale = Locales.of(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);
        String currency = Messages.currencyLabel();

        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        BigDecimal retention = BigDecimal.ZERO;
        BigDecimal cleared = BigDecimal.ZERO;
        LocalDate date = null;
        Set<UUID> advanceIds = new LinkedHashSet<>();
        for (ProducerPurchaseEntity r : lines) {
            weight = weight.add(nz(r.weightKg));
            gross = gross.add(nz(r.amount));
            margin = margin.add(nz(r.delegateMargin));
            retention = retention.add(nz(r.delegateRetention));
            // Ce que cette livraison a apuré : la part de la livraison et
            // celle de la rémunération réellement imputées sur l'avance.
            cleared = cleared.add(nz(r.amountPaid)).add(nz(r.marginImputed));
            if (date == null) date = r.date;
            if (r.collectorAdvanceId != null) advanceIds.add(r.collectorAdvanceId);
        }
        BigDecimal net = gross.add(margin).subtract(cleared).subtract(retention);

        // Le compte avant et après : la ligne du grand livre qui porte
        // cette livraison, et celle qui la précède.
        DelegateLedgerDto ledger = accounts.ledger(delegateSupplierId, campaignId);
        BigDecimal balanceAfter = nz(ledger.previousBalance());
        BigDecimal balanceBefore = nz(ledger.previousBalance());
        for (DelegateLedgerDto.Line line : ledger.lines()) {
            if (deliveryRef.equals(line.ref()) || deliveryRef.equals(line.fieldNoteRef())) {
                balanceAfter = nz(line.netBalance());
                break;
            }
            balanceBefore = nz(line.netBalance());
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 40, 40, 36, 36);
            PdfWriter.getInstance(doc, out).setPageEvent(new PdfBranding());
            doc.open();

            Font orgFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Color.BLACK);
            Font refFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Color.BLACK);
            Font noteFont = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);

            doc.add(new Paragraph(organization, orgFont));
            Paragraph title = new Paragraph(
                    Messages.msg(locale, "m.col-invoice-title").toUpperCase(locale), titleFont);
            title.setSpacingBefore(12);
            doc.add(title);
            Paragraph ref = new Paragraph(deliveryRef, refFont);
            ref.setSpacingAfter(12);
            doc.add(ref);

            PdfPTable header = new PdfPTable(new float[]{1, 1, 1, 1});
            header.setWidthPercentage(100);
            header.setSpacingAfter(12);
            addField(header, Messages.msg(locale, "m.col-invoice-date"),
                    date != null ? DATE.format(date) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-invoice-collector"),
                    nullSafe(delegate.name), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-invoice-collector-code"),
                    nullSafe(delegate.code), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-invoice-campaign"),
                    nullSafe(ledger.campaignLabel()), labelFont, valueFont);
            doc.add(header);

            doc.add(detail(locale, lines, weight, gross, currency, cellFont, valueFont));
            doc.add(recap(locale, balanceBefore, gross, margin, cleared, retention, net,
                    balanceAfter, advanceRefs(advanceIds), currency, cellFont, valueFont));

            Paragraph words = new Paragraph(
                    Messages.msg(locale, "m.col-invoice-in-words",
                            AmountInWords.of(net, locale), currency), cellFont);
            words.setSpacingBefore(14);
            doc.add(words);

            Paragraph note = new Paragraph(Messages.msg(locale, "m.col-invoice-footer"), noteFont);
            note.setSpacingBefore(6);
            doc.add(note);

            doc.add(visas(locale, valueFont));

            doc.close();
            return out.toByteArray();
        } catch (BusinessException | NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(Messages.msg("m.col-invoice-generation-failed"), ex);
        }
    }

    /** Les références des avances que cette livraison a apurées. */
    private String advanceRefs(Set<UUID> ids) {
        if (ids.isEmpty()) return "";
        List<String> refs = new ArrayList<>();
        for (UUID id : ids) {
            advances.findById(id).map(a -> a.ref).filter(java.util.Objects::nonNull)
                    .ifPresent(refs::add);
        }
        return String.join(", ", refs);
    }

    /** Le détail de l'achat : un reçu par ligne, comme le carnet. */
    private PdfPTable detail(Locale locale, List<ProducerPurchaseEntity> lines,
                             BigDecimal weight, BigDecimal gross, String currency,
                             Font cellFont, Font headFont) {
        PdfPTable table = new PdfPTable(new float[]{1.1f, 2.2f, 1.2f, 1.2f, 1.4f});
        table.setWidthPercentage(100);
        for (String key : new String[]{"m.col-invoice-col-date", "m.col-invoice-col-producer",
                "m.col-invoice-col-weight", "m.col-invoice-col-price", "m.col-invoice-col-amount"}) {
            head(table, Messages.msg(locale, key), headFont);
        }
        for (ProducerPurchaseEntity r : lines) {
            cell(table, r.date == null ? "" : DATE.format(r.date), cellFont, Element.ALIGN_LEFT);
            cell(table, r.counterpartyName() == null ? "" : r.counterpartyName(),
                    cellFont, Element.ALIGN_LEFT);
            cell(table, money(r.weightKg), cellFont, Element.ALIGN_RIGHT);
            cell(table, money(r.guaranteedPricePerKg), cellFont, Element.ALIGN_RIGHT);
            cell(table, money(r.amount), cellFont, Element.ALIGN_RIGHT);
        }
        cell(table, Messages.msg(locale, "m.col-invoice-gross"), headFont, Element.ALIGN_LEFT);
        cell(table, "", headFont, Element.ALIGN_LEFT);
        cell(table, money(weight), headFont, Element.ALIGN_RIGHT);
        cell(table, "", headFont, Element.ALIGN_RIGHT);
        cell(table, money(gross) + " " + currency, headFont, Element.ALIGN_RIGHT);
        return table;
    }

    /**
     * Le décompte : ce qui s'ajoute, ce qui se retient, ce qui reste à
     * verser, et le compte courant de part et d'autre.
     */
    private PdfPTable recap(Locale locale, BigDecimal balanceBefore, BigDecimal gross,
                            BigDecimal margin, BigDecimal cleared, BigDecimal retention,
                            BigDecimal net, BigDecimal balanceAfter, String advanceRefs,
                            String currency, Font cellFont, Font headFont) {
        PdfPTable table = new PdfPTable(new float[]{3, 2});
        table.setWidthPercentage(70);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(12);

        line(table, Messages.msg(locale, "m.col-invoice-balance-before"),
                money(balanceBefore) + " " + currency, cellFont, cellFont);
        line(table, Messages.msg(locale, "m.col-invoice-gross"),
                money(gross) + " " + currency, cellFont, cellFont);
        line(table, Messages.msg(locale, "m.col-invoice-margin"),
                "+ " + money(margin) + " " + currency, cellFont, cellFont);
        line(table, advanceRefs.isBlank()
                        ? Messages.msg(locale, "m.col-invoice-advances")
                        : Messages.msg(locale, "m.col-invoice-advances-refs", advanceRefs),
                "- " + money(cleared) + " " + currency, cellFont, cellFont);
        line(table, Messages.msg(locale, "m.col-invoice-retention"),
                "- " + money(retention) + " " + currency, cellFont, cellFont);
        line(table, Messages.msg(locale, "m.col-invoice-net"),
                money(net) + " " + currency, headFont, headFont);
        line(table, Messages.msg(locale, "m.col-invoice-balance-after"),
                money(balanceAfter) + " " + currency, cellFont, cellFont);
        return table;
    }

    /** Deux visas : la coopérative, et le collecteur qui approuve. */
    private PdfPTable visas(Locale locale, Font font) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(30);
        for (String key : new String[]{"m.col-invoice-visa-coop", "m.col-invoice-visa-collector"}) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setFixedHeight(70);
            cell.addElement(new Paragraph(Messages.msg(locale, key), font));
            table.addCell(cell);
        }
        return table;
    }

    private static void line(PdfPTable table, String label, String value,
                             Font labelFont, Font valueFont) {
        cell(table, label, labelFont, Element.ALIGN_LEFT);
        cell(table, value, valueFont, Element.ALIGN_RIGHT);
    }

    private static void addField(PdfPTable table, String label, String value,
                                 Font labelFont, Font valueFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8);
        cell.addElement(new Paragraph(label.toUpperCase(Locale.FRANCE), labelFont));
        cell.addElement(new Paragraph(value, valueFont));
        table.addCell(cell);
    }

    private static void head(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(HEADER_BG);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private static void cell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setHorizontalAlignment(align);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private static String money(BigDecimal value) {
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(Locale.FRANCE);
        nf.setMaximumFractionDigits(0);
        return nf.format(value == null ? BigDecimal.ZERO : value);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
