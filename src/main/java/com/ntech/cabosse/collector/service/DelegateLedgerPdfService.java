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
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.export.AmountInWords;
import com.ntech.cabosse.shared.export.PdfBranding;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Le relevé du compte d'un délégué à une date (demandé le 14/10/2026).
 *
 * <p>Le conseil veut, sur papier, comment le solde s'est formé :
 * l'avance versée, la matière livrée, la mise en compte retenue, et ce
 * qui reste dû à chaque étape. L'écran le montre depuis longtemps ; ce
 * qui manquait, c'est le document que l'on remet et que l'on signe.</p>
 *
 * <p>Le relevé s'arrête à la date demandée : un solde est toujours celui
 * d'un jour, et un document daté d'aujourd'hui qui additionne des
 * opérations de demain ne se rapproche de rien.</p>
 */
@ApplicationScoped
public class DelegateLedgerPdfService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color HEADER_BG = new Color(0xF0, 0xF0, 0xF0);

    @Inject DelegateAccountService accounts;
    @Inject TenantRepository tenants;
    @Inject com.ntech.cabosse.shared.export.PdfLetterhead letterhead;
    @Inject TenantContext tenantContext;

    public byte[] build(UUID delegateSupplierId, UUID campaignId, LocalDate asOf) {
        DelegateLedgerDto ledger = accounts.ledger(delegateSupplierId, campaignId);
        LocalDate cutoff = asOf != null ? asOf : LocalDate.now();
        List<DelegateLedgerDto.Line> lines = ledger.lines().stream()
                .filter(l -> l.date() == null || !l.date().isAfter(cutoff))
                .toList();

        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        Locale locale = Locales.of(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);
        String currency = Messages.currencyLabel();

        // Le solde à la date, et non le solde du jour : c'est la dernière
        // ligne retenue qui le porte.
        BigDecimal balance = lines.isEmpty()
                ? nz(ledger.previousBalance())
                : nz(lines.get(lines.size() - 1).netBalance());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
            PdfWriter.getInstance(doc, out).setPageEvent(new PdfBranding());
            doc.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Color.BLACK);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
            Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.BLACK);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Color.BLACK);
            Font noteFont = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);

            doc.add(letterhead.of(tenant));
            Paragraph title = new Paragraph(
                    Messages.msg(locale, "m.col-ledger-title").toUpperCase(locale), titleFont);
            title.setSpacingBefore(12);
            title.setSpacingAfter(10);
            doc.add(title);

            PdfPTable header = new PdfPTable(new float[]{1, 1, 1, 1});
            header.setWidthPercentage(100);
            header.setSpacingAfter(12);
            addField(header, Messages.msg(locale, "m.col-ledger-delegate"),
                    nullSafe(ledger.delegateName()), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-ledger-code"),
                    nullSafe(ledger.delegateCode()), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-ledger-campaign"),
                    nullSafe(ledger.campaignLabel()), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-ledger-as-of"),
                    DATE.format(cutoff), labelFont, valueFont);
            doc.add(header);

            doc.add(linesTable(locale, ledger, lines, currency, headFont, cellFont, valueFont));

            Paragraph closing = new Paragraph(
                    Messages.msg(locale, "m.col-ledger-balance",
                            com.ntech.cabosse.shared.export.PdfNumbers.money(balance)), valueFont);
            closing.setSpacingBefore(14);
            doc.add(closing);
            Paragraph words = new Paragraph(
                    Messages.msg(locale, "m.col-voucher-in-words",
                            AmountInWords.of(balance, locale), currency), cellFont);
            words.setSpacingBefore(4);
            doc.add(words);

            Paragraph note = new Paragraph(Messages.msg(locale, "m.col-ledger-footer"), noteFont);
            note.setSpacingBefore(8);
            doc.add(note);

            doc.close();
            return out.toByteArray();
        } catch (BusinessException | NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(Messages.msg("m.col-ledger-generation-failed"), ex);
        }
    }

    /**
     * Opération par opération, avec le solde après chacune : c'est la
     * colonne que le délégué suit du doigt.
     */
    private PdfPTable linesTable(Locale locale, DelegateLedgerDto ledger,
                                 List<DelegateLedgerDto.Line> lines, String currency,
                                 Font headFont, Font cellFont, Font totalFont) {
        PdfPTable table = new PdfPTable(new float[]{1.2f, 1.4f, 1.6f, 1.4f, 1.2f, 1.4f, 1.2f, 1.4f});
        table.setWidthPercentage(100);
        // L'unité et la devise vivent dans l'en-tête : une colonne de
        // chiffres se lit mieux sans son unité répétée à chaque ligne
        // (15/10/2026).
        head(table, Messages.msg(locale, "m.col-ledger-col-date"), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-operation"), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-ref"), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-advanced", currency), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-weight"), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-delivered", currency), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-retention", currency), headFont);
        head(table, Messages.msg(locale, "m.col-ledger-col-balance", currency), headFont);

        // Le report des campagnes antérieures ouvre le relevé : sans lui,
        // la première ligne semblerait partir de zéro.
        cell(table, "", cellFont, Element.ALIGN_LEFT);
        cell(table, Messages.msg(locale, "m.col-ledger-previous"), cellFont, Element.ALIGN_LEFT);
        cell(table, "", cellFont, Element.ALIGN_LEFT);
        cell(table, "", cellFont, Element.ALIGN_RIGHT);
        cell(table, "", cellFont, Element.ALIGN_RIGHT);
        cell(table, "", cellFont, Element.ALIGN_RIGHT);
        cell(table, "", cellFont, Element.ALIGN_RIGHT);
        cell(table, com.ntech.cabosse.shared.export.PdfNumbers.money(ledger.previousBalance()), cellFont, Element.ALIGN_RIGHT);

        for (DelegateLedgerDto.Line line : lines) {
            cell(table, line.date() == null ? "" : DATE.format(line.date()),
                    cellFont, Element.ALIGN_LEFT);
            cell(table, operationLabel(locale, line.operation()), cellFont, Element.ALIGN_LEFT);
            cell(table, join(line.ref(), line.fieldNoteRef()), cellFont, Element.ALIGN_LEFT);
            cell(table, com.ntech.cabosse.shared.export.PdfNumbers.money(line.advanced()), cellFont, Element.ALIGN_RIGHT);
            cell(table, com.ntech.cabosse.shared.export.PdfNumbers.quantity(line.weightKg()), cellFont, Element.ALIGN_RIGHT);
            cell(table, com.ntech.cabosse.shared.export.PdfNumbers.money(line.delivered()), cellFont, Element.ALIGN_RIGHT);
            cell(table, com.ntech.cabosse.shared.export.PdfNumbers.money(line.retention()), cellFont, Element.ALIGN_RIGHT);
            cell(table, com.ntech.cabosse.shared.export.PdfNumbers.money(line.netBalance()), totalFont, Element.ALIGN_RIGHT);
        }
        return table;
    }

    private static String operationLabel(Locale locale, DelegateLedgerDto.Operation operation) {
        if (operation == null) return "";
        return switch (operation) {
            case ADVANCE -> Messages.msg(locale, "m.col-ledger-op-advance");
            case DELIVERY -> Messages.msg(locale, "m.col-ledger-op-delivery");
            case SETTLEMENT -> Messages.msg(locale, "m.col-ledger-op-settlement");
        };
    }

    private static String join(String ref, String fieldNoteRef) {
        if (fieldNoteRef == null || fieldNoteRef.isBlank()) return nullSafe(ref);
        return nullSafe(ref) + " · " + fieldNoteRef;
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

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
