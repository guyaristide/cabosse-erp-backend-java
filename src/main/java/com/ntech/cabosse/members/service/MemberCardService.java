package com.ntech.cabosse.members.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Carte de membre PDF (backlog MEM-03) : document A6 paysage au nom de la
 * structure du tenant, remis au coopérateur après validation de son
 * adhésion. Réservée aux membres actifs ou suspendus — un dossier en
 * attente ou radié n'a pas de carte.
 */
@ApplicationScoped
public class MemberCardService {

    /**
     * Format de date de la carte. Le jour avant le mois vaut pour les deux
     * langues servies : l'anglais britannique, usage de l'Afrique de l'Ouest
     * anglophone, l'écrit comme le français. Basculer sur le format
     * américain rendrait 03/04 ambigu d'un porteur de carte à l'autre.
     */
    private static final DateTimeFormatter CARD_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    @Inject MemberRepository members;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public byte[] buildCard(UUID memberId) {
        MemberEntity m = members.findById(memberId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.mbr-member-not-found", memberId)));
        if (m.status == MemberStatus.PENDING) {
            throw new BusinessException(Messages.msg("m.mbr-card-requires-approved"));
        }
        if (m.status == MemberStatus.RETIRED || m.status == MemberStatus.INACTIVE) {
            throw new BusinessException(Messages.msg("m.mbr-card-retired-or-inactive"));
        }
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        String organization = tenant != null ? tenant.name : "";
        // La carte est remise au coopérateur, pas à l'agent qui l'imprime :
        // elle suit la langue de la structure, comme un courriel suit celle
        // de son destinataire.
        Locale locale = Locales.of(tenant != null && tenant.preferences != null
                ? tenant.preferences.language : null);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // A6 paysage : 148 × 105 mm.
            Document doc = new Document(new Rectangle(420, 298), 24, 24, 20, 20);
            PdfWriter.getInstance(doc, out)
                    .setPageEvent(new com.ntech.cabosse.shared.export.PdfBranding());
            doc.open();

            Font orgFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0x66, 0x66, 0x66));
            Font nameFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.BLACK);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(0x66, 0x66, 0x66));
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

            Paragraph org = new Paragraph(organization, orgFont);
            doc.add(org);
            Paragraph title = new Paragraph("CARTE DE MEMBRE", titleFont);
            title.setSpacingAfter(10);
            doc.add(title);

            Paragraph name = new Paragraph(m.name, nameFont);
            name.setSpacingAfter(12);
            doc.add(name);

            PdfPTable table = new PdfPTable(new float[]{1, 1});
            table.setWidthPercentage(100);
            addField(table, Messages.msg(locale, "m.mbr-card-number"), m.code, labelFont, valueFont);
            addField(table, Messages.msg(locale, "m.mbr-card-village"),
                    m.village != null ? m.village : "-",
                    labelFont, valueFont);
            addField(table, Messages.msg(locale, "m.mbr-card-joined"),
                    m.joinedAt != null ? CARD_DATE.format(m.joinedAt) : "-", labelFont, valueFont);
            addField(table, Messages.msg(locale, "m.mbr-card-issued"),
                    CARD_DATE.format(LocalDate.now()), labelFont, valueFont);
            doc.add(table);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(Messages.msg("m.mbr-card-generation-failed", e.getMessage()));
        }
    }

    private static void addField(PdfPTable table, String label, String value,
                                 Font labelFont, Font valueFont) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8);
        cell.addElement(new Paragraph(label.toUpperCase(Locale.FRANCE), labelFont));
        Paragraph v = new Paragraph(value, valueFont);
        v.setAlignment(Element.ALIGN_LEFT);
        cell.addElement(v);
        table.addCell(cell);
    }
}
