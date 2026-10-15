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
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * La pièce d'une avance consentie à un délégué (demandé le 14/10/2026).
 *
 * <p>Le conseil veut un document par avance versée, remis au délégué et
 * conservé par la coopérative : qui a reçu, combien, quand, par quel
 * instrument, et où en est son compte après ce versement. Jusqu'ici
 * l'avance ne vivait qu'à l'écran.</p>
 *
 * <p>Ce n'est pas une facture et le document ne le dit pas : une avance
 * ne vend rien, elle met des fonds en main. Le compte courant du délégué
 * s'en trouve débiteur, et c'est ce solde que la pièce porte en bas,
 * pour que celui qui signe sache ce qu'il doit après avoir reçu.</p>
 */
@ApplicationScoped
public class AdvanceVoucherService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color HEADER_BG = new Color(0xF0, 0xF0, 0xF0);

    @Inject CollectorAdvanceRepository advances;
    @Inject DelegateAccountService accounts;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public byte[] build(UUID advanceId) {
        CollectorAdvanceEntity e = advances.findById(advanceId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-advance-not-found", advanceId)));
        // Une avance qui n'est pas sortie n'a pas de pièce : elle n'a
        // encore rien mis dans la main de personne.
        if (e.status == CollectorAdvanceStatus.PENDING_APPROVAL
                || e.status == CollectorAdvanceStatus.APPROVED
                || e.status == CollectorAdvanceStatus.REJECTED) {
            throw new BusinessException(Messages.msg("m.col-voucher-not-disbursed", e.ref));
        }

        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        Locale locale = Locales.of(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);
        String currency = Messages.currencyLabel();

        BigDecimal amount = e.effectiveAmount();
        BigDecimal balance = accounts.outstanding(e.delegateSupplierId, e.campaignId);

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
            Font noteFont = FontFactory.getFont(FontFactory.HELVETICA, 9, MUTED);

            doc.add(new Paragraph(organization, orgFont));
            Paragraph beneficiary = new Paragraph(nullSafe(e.delegateName), valueFont);
            beneficiary.setAlignment(Element.ALIGN_CENTER);
            beneficiary.setSpacingBefore(10);
            doc.add(beneficiary);

            Paragraph title = new Paragraph(
                    Messages.msg(locale, "m.col-voucher-title").toUpperCase(locale), titleFont);
            title.setSpacingBefore(14);
            doc.add(title);
            Paragraph ref = new Paragraph(e.ref, refFont);
            ref.setSpacingAfter(12);
            doc.add(ref);

            PdfPTable header = new PdfPTable(new float[]{1, 1, 1});
            header.setWidthPercentage(100);
            header.setSpacingAfter(14);
            addField(header, Messages.msg(locale, "m.col-voucher-date"),
                    e.advanceDate != null ? DATE.format(e.advanceDate) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-voucher-beneficiary"),
                    nullSafe(e.delegateName), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.col-voucher-reference"),
                    instrument(locale, e), labelFont, valueFont);
            doc.add(header);

            doc.add(lineTable(locale, e, amount, currency, cellFont, valueFont));
            doc.add(totals(locale, amount, balance, currency, cellFont, valueFont));

            Paragraph words = new Paragraph(
                    Messages.msg(locale, "m.col-voucher-in-words",
                            AmountInWords.of(amount, locale), currency), cellFont);
            words.setSpacingBefore(16);
            doc.add(words);

            Paragraph note = new Paragraph(Messages.msg(locale, "m.col-voucher-footer"), noteFont);
            note.setSpacingBefore(6);
            doc.add(note);

            doc.add(visas(locale, valueFont));

            doc.close();
            return out.toByteArray();
        } catch (BusinessException | NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(Messages.msg("m.col-voucher-generation-failed"), ex);
        }
    }

    /** L'instrument réellement employé, avec sa référence quand il y en a une. */
    private static String instrument(Locale locale, CollectorAdvanceEntity e) {
        String method = e.paymentMethod == null ? "-"
                : com.ntech.cabosse.shared.export.ExportEnumLabels.paymentMethod(e.paymentMethod.name());
        return e.paymentRef == null || e.paymentRef.isBlank()
                ? method : method + " n° " + e.paymentRef;
    }

    /** La ligne de la pièce : une avance, en une fois, pour son montant. */
    private PdfPTable lineTable(Locale locale, CollectorAdvanceEntity e, BigDecimal amount,
                                String currency, Font cellFont, Font headFont) {
        PdfPTable table = new PdfPTable(new float[]{4, 1, 2});
        table.setWidthPercentage(100);
        head(table, Messages.msg(locale, "m.col-voucher-col-description"), headFont, Element.ALIGN_LEFT);
        head(table, Messages.msg(locale, "m.col-voucher-col-quantity"), headFont, Element.ALIGN_RIGHT);
        head(table, Messages.msg(locale, "m.col-voucher-col-amount"), headFont, Element.ALIGN_RIGHT);

        cell(table, Messages.msg(locale, "m.col-voucher-line-label",
                e.ref, nullSafe(e.delegateName)), cellFont, Element.ALIGN_LEFT);
        cell(table, "1", cellFont, Element.ALIGN_RIGHT);
        cell(table, money(amount) + " " + currency, cellFont, Element.ALIGN_RIGHT);
        return table;
    }

    /**
     * Le total versé, puis le solde du compte après ce versement : celui
     * qui signe doit lire ce qu'il doit, pas seulement ce qu'il reçoit.
     */
    private PdfPTable totals(Locale locale, BigDecimal amount, BigDecimal balance,
                             String currency, Font cellFont, Font headFont) {
        PdfPTable table = new PdfPTable(new float[]{3, 2});
        table.setWidthPercentage(60);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setSpacingBefore(10);
        cell(table, Messages.msg(locale, "m.col-voucher-total"), headFont, Element.ALIGN_LEFT);
        cell(table, money(amount) + " " + currency, headFont, Element.ALIGN_RIGHT);
        cell(table, Messages.msg(locale, "m.col-voucher-balance"), cellFont, Element.ALIGN_LEFT);
        cell(table, money(balance) + " " + currency, cellFont, Element.ALIGN_RIGHT);
        return table;
    }

    /** Deux visas : celui qui remet les fonds, et celui qui les reçoit. */
    private PdfPTable visas(Locale locale, Font font) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        for (String key : new String[]{"m.col-voucher-visa-payer", "m.col-voucher-visa-beneficiary"}) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setFixedHeight(70);
            cell.addElement(new Paragraph(Messages.msg(locale, key), font));
            table.addCell(cell);
        }
        return table;
    }

    /** Une case étiquette + valeur de l'en-tête, sans bordure. */
    private static void addField(PdfPTable table, String label, String value,
                                 Font labelFont, Font valueFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8);
        cell.addElement(new Paragraph(label.toUpperCase(Locale.FRANCE), labelFont));
        cell.addElement(new Paragraph(value, valueFont));
        table.addCell(cell);
    }

    private static void head(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(HEADER_BG);
        cell.setHorizontalAlignment(align);
        cell.setPadding(6);
        table.addCell(cell);
    }

    private static void cell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setHorizontalAlignment(align);
        cell.setPadding(6);
        table.addCell(cell);
    }

    /** Le montant, groupé par milliers comme le reste des documents. */
    private static String money(BigDecimal value) {
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(Locale.FRANCE);
        nf.setMaximumFractionDigits(0);
        return nf.format(value == null ? BigDecimal.ZERO : value);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }
}
