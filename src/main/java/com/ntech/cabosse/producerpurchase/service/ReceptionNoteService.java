package com.ntech.cabosse.producerpurchase.service;

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
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.entity.PurchaseWeighing;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
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
import java.util.UUID;

/**
 * Bordereau de réception PDF (épic magasin, CE-184).
 *
 * <p>Reproduit le carnet à souche de la coopérative : en-tête campagne,
 * produit, date, numéro, camion, fournisseur, lignes de pesée (brut,
 * décote, net), totaux, et deux zones de visa, magasinier et fournisseur.
 * Le magasinier l'imprime en deux copies et fait signer sur place : c'est
 * la pièce de la réception, réimprimable depuis sa fiche.</p>
 *
 * <p>Aucun montant n'y figure : le carnet réel n'en porte pas, le
 * bordereau constate la matière, le reçu d'achat constate l'argent.</p>
 */
@ApplicationScoped
public class ReceptionNoteService {

    /** Jour avant le mois, lisible pareil dans les deux langues servies. */
    private static final DateTimeFormatter NOTE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private static final Color MUTED = new Color(0x66, 0x66, 0x66);
    private static final Color HEADER_BG = new Color(0xF0, 0xF0, 0xF0);

    @Inject ProducerPurchaseRepository purchases;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public byte[] build(UUID purchaseId) {
        ProducerPurchaseEntity e = purchases.findById(purchaseId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.ppu-receipt-not-found", purchaseId)));
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        // Le bordereau est signé par le fournisseur : il suit la langue de
        // la structure, comme la carte de membre.
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
                    Messages.msg(locale, "m.ppu-note-title").toUpperCase(locale), titleFont);
            title.setSpacingBefore(6);
            doc.add(title);
            Paragraph ref = new Paragraph(
                    Messages.msg(locale, "m.ppu-note-number", e.ref), refFont);
            ref.setSpacingAfter(12);
            doc.add(ref);

            PdfPTable header = new PdfPTable(new float[]{1, 1, 1});
            header.setWidthPercentage(100);
            header.setSpacingAfter(14);
            addField(header, Messages.msg(locale, "m.ppu-note-campaign"),
                    e.campaignYear != null ? String.valueOf(e.campaignYear) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-product"),
                    nullSafe(e.articleName), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-date"),
                    e.date != null ? NOTE_DATE.format(e.date) : "-", labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-truck"),
                    e.truckNumber != null ? e.truckNumber : Messages.msg(locale, "m.ppu-note-no-truck"),
                    labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-supplier"), supplierOf(e), labelFont, valueFont);
            addField(header, Messages.msg(locale, "m.ppu-note-supplier-code"),
                    supplierCodeOf(e), labelFont, valueFont);
            doc.add(header);

            doc.add(weighingsTable(locale, e, cellFont, totalFont));

            if (e.nbSacs != null) {
                Paragraph sacks = new Paragraph(
                        Messages.msg(locale, "m.ppu-note-sacks", String.valueOf(e.nbSacs)), valueFont);
                sacks.setSpacingBefore(8);
                doc.add(sacks);
            }

            doc.add(visas(locale, valueFont));

            doc.close();
            return out.toByteArray();
        } catch (BusinessException | NotFoundException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(Messages.msg("m.ppu-note-generation-failed", ex.getMessage()));
        }
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

    /** Le fournisseur du carnet : le délégué qui a livré, sinon le producteur. */
    private static String supplierOf(ProducerPurchaseEntity e) {
        return e.delegateName != null ? e.delegateName : nullSafe(e.producerName);
    }

    private static String supplierCodeOf(ProducerPurchaseEntity e) {
        if (e.delegateName != null) return "-";
        return e.producerCode != null ? e.producerCode : "-";
    }

    /**
     * Les lignes de pesée du carnet. Un reçu saisi sans le détail rend une
     * ligne unique au poids du reçu : le document reste imprimable, il dit
     * simplement ce qu'on sait.
     */
    private PdfPTable weighingsTable(Locale locale, ProducerPurchaseEntity e,
                                     Font cellFont, Font totalFont) {
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        PdfPTable table = new PdfPTable(new float[]{0.6f, 1, 1, 1});
        table.setWidthPercentage(100);
        for (String key : List.of("m.ppu-note-col-index", "m.ppu-note-col-gross",
                "m.ppu-note-col-bags", "m.ppu-note-col-net")) {
            PdfPCell cell = new PdfPCell(new Paragraph(Messages.msg(locale, key), headFont));
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(6);
            table.addCell(cell);
        }

        BigDecimal totalGross = BigDecimal.ZERO;
        int totalBags = 0;
        BigDecimal totalNet = BigDecimal.ZERO;
        List<PurchaseWeighing> weighings = e.weighings != null && !e.weighings.isEmpty()
                ? e.weighings : List.of(singleLineOf(e));
        int index = 1;
        for (PurchaseWeighing w : weighings) {
            addRow(table, cellFont, String.valueOf(index++), w.grossKg,
                    w.bagsCount != null ? String.valueOf(w.bagsCount) : "-", w.netKg);
            totalGross = totalGross.add(nz(w.grossKg));
            totalBags += w.bagsCount != null ? w.bagsCount : 0;
            totalNet = totalNet.add(nz(w.netKg));
        }
        addRow(table, totalFont, Messages.msg(locale, "m.ppu-note-totals"),
                totalGross, String.valueOf(totalBags), totalNet);
        return table;
    }

    /** Le reçu sans pesées : brut inconnu, net = poids, sacs du reçu. */
    private static PurchaseWeighing singleLineOf(ProducerPurchaseEntity e) {
        PurchaseWeighing w = new PurchaseWeighing();
        w.netKg = e.weightKg;
        w.bagsCount = e.nbSacs;
        return w;
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

    /** Les deux zones de signature, comme sur le carnet. */
    private static PdfPTable visas(Locale locale, Font font) {
        PdfPTable table = new PdfPTable(new float[]{1, 1});
        table.setWidthPercentage(100);
        table.setSpacingBefore(36);
        for (String key : List.of("m.ppu-note-visa-warehouse", "m.ppu-note-visa-supplier")) {
            PdfPCell cell = new PdfPCell(new Paragraph(Messages.msg(locale, key), font));
            cell.setBorder(Rectangle.TOP);
            cell.setPaddingTop(8);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        return table;
    }

    private static String nullSafe(String v) {
        return v != null ? v : "";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
