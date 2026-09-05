package com.ntech.cabosse.dispatch.service;

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
import com.ntech.cabosse.dispatch.entity.DispatchLine;
import com.ntech.cabosse.dispatch.entity.DispatchNoteEntity;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Bordereau de sortie PDF (CE-195), sur le patron du bordereau de
 * réception : en-tête campagne, produit, date, numéro, camion, client,
 * lignes d'appel (BR, brut, sacs, net), totaux, double visa. C'est la
 * pièce signée qui accompagne le camion ; aucun montant n'y figure, la
 * facture parle argent, le bordereau parle matière.
 */
@ApplicationScoped
public class DispatchNotePdfService {

    private static final DateTimeFormatter NOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color HEADER_BG = new Color(0xF0, 0xF0, 0xF0);

    @Inject DispatchNoteService notes;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public byte[] build(java.util.UUID noteId) {
        DispatchNoteEntity e = notes.loadOrFail(noteId);
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        Locale locale = Locales.of(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 40, 40, 36, 36);
            PdfWriter.getInstance(doc, out).setPageEvent(new PdfBranding());
            doc.open();

            Font orgFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, Color.BLACK);
            Font refFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
            Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

            doc.add(new Paragraph(organization, orgFont));
            Paragraph title = new Paragraph(
                    Messages.msg(locale, "m.dsp-note-title").toUpperCase(locale), titleFont);
            title.setSpacingBefore(6);
            doc.add(title);
            Paragraph ref = new Paragraph(Messages.msg(locale, "m.ppu-note-number", e.ref), refFont);
            ref.setSpacingAfter(12);
            doc.add(ref);

            PdfPTable header = new PdfPTable(new float[]{1, 1, 1});
            header.setWidthPercentage(100);
            header.setSpacingAfter(14);
            addField(header, Messages.msg(locale, "m.ppu-note-campaign"),
                    e.campaignYear != null ? String.valueOf(e.campaignYear) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-product"),
                    e.articleName != null ? e.articleName : "", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-date"),
                    e.date != null ? NOTE_DATE.format(e.date) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-truck"),
                    e.truckNumber != null ? e.truckNumber
                            : Messages.msg(locale, "m.ppu-note-no-truck"), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.dsp-note-customer"),
                    e.customerName != null ? e.customerName : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.dsp-note-site"),
                    e.siteName != null ? e.siteName : "-", labelFont, valueFont);
            doc.add(header);

            doc.add(linesTable(locale, e, cellFont, totalFont));
            doc.add(visas(locale, valueFont));

            doc.close();
            return out.toByteArray();
        } catch (BusinessException | NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(Messages.msg("m.ppu-note-generation-failed", ex.getMessage()));
        }
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

    /** Les appels du carnet : N° BR, brut, sacs, net, et les totaux. */
    private PdfPTable linesTable(Locale locale, DispatchNoteEntity e,
                                 Font cellFont, Font totalFont) {
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        PdfPTable table = new PdfPTable(new float[]{1.2f, 1, 0.8f, 1});
        table.setWidthPercentage(100);
        for (String key : List.of("m.dsp-note-col-receipt", "m.ppu-note-col-gross",
                "m.ppu-note-col-bags", "m.ppu-note-col-net")) {
            PdfPCell cell = new PdfPCell(new Paragraph(Messages.msg(locale, key), headFont));
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(6);
            table.addCell(cell);
        }
        for (DispatchLine line : e.lines) {
            addRow(table, cellFont, line.receiptRef, line.grossKg,
                    line.bagsCount != null ? String.valueOf(line.bagsCount) : "-", line.netKg);
        }
        addRow(table, totalFont, Messages.msg(locale, "m.ppu-note-totals"),
                e.totalGrossKg, String.valueOf(e.totalBags != null ? e.totalBags : 0), e.totalNetKg);
        return table;
    }

    private static void addRow(PdfPTable table, Font font, String label,
                               BigDecimal gross, String bags, BigDecimal net) {
        PdfPCell first = new PdfPCell(new Paragraph(label, font));
        first.setPadding(6);
        table.addCell(first);
        for (String value : new String[]{
                gross != null ? gross.stripTrailingZeros().toPlainString() : "-",
                bags,
                net != null ? net.stripTrailingZeros().toPlainString() : "-"}) {
            PdfPCell cell = new PdfPCell(new Paragraph(value, font));
            cell.setPadding(6);
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    /** Le magasinier qui charge, le chauffeur qui emporte. */
    private static PdfPTable visas(Locale locale, Font font) {
        PdfPTable table = new PdfPTable(new float[]{1, 1});
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        for (String key : List.of("m.ppu-note-visa-warehouse", "m.dsp-note-visa-driver")) {
            PdfPCell cell = new PdfPCell(new Paragraph(Messages.msg(locale, key), font));
            cell.setBorder(Rectangle.TOP);
            cell.setPaddingTop(8);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        return table;
    }
}
