package com.ntech.cabosse.shared.export;

import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.service.TenantLogoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.awt.Color;
import java.io.InputStream;
import java.util.UUID;

/**
 * L'en-tête d'un document remis à un tiers : le logo de la structure et
 * son nom.
 *
 * <p>Une pièce que la coopérative fait signer porte sa marque, pas
 * seulement celle de l'éditeur. Le filigrane de {@link PdfBranding}
 * reste en fond ; c'est le logo du client qui ouvre la page (demandé le
 * 15/10/2026).</p>
 *
 * <p>Le logo est facultatif, et son absence ne fait jamais échouer un
 * document : une structure qui n'en a pas téléversé voit son nom seul,
 * comme avant.</p>
 */
@ApplicationScoped
public class PdfLetterhead {

    private static final Logger LOG = Logger.getLogger(PdfLetterhead.class);

    /** Assez grand pour se reconnaître, assez petit pour ne pas voler la page. */
    private static final float LOGO_HEIGHT = 46f;

    @Inject TenantLogoService logos;

    /**
     * Le bandeau de tête : le logo à gauche quand il existe, le nom de la
     * structure à côté.
     */
    public PdfPTable of(TenantEntity tenant) {
        String organization = tenant != null && tenant.name != null ? tenant.name : "";
        Font orgFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.BLACK);

        Image logo = tenant == null ? null : load(tenant.id);
        PdfPTable table = logo == null
                ? new PdfPTable(new float[]{1})
                : new PdfPTable(new float[]{1, 5});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);

        if (logo != null) {
            logo.scaleToFit(LOGO_HEIGHT * 3, LOGO_HEIGHT);
            PdfPCell cell = new PdfPCell(logo, false);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setPaddingRight(10);
            table.addCell(cell);
        }

        PdfPCell name = new PdfPCell(new Paragraph(organization, orgFont));
        name.setBorder(Rectangle.NO_BORDER);
        name.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(name);
        return table;
    }

    /**
     * Le logo, ou rien. Un fichier illisible ou absent ne doit pas
     * empêcher d'imprimer une pièce que quelqu'un attend.
     */
    private Image load(UUID tenantId) {
        try (InputStream in = logos.openLogo(tenantId).content()) {
            return Image.getInstance(in.readAllBytes());
        } catch (RuntimeException | java.io.IOException e) {
            LOG.debugf("Logo du tenant %s indisponible pour l'en-tête PDF : %s",
                    tenantId, e.getMessage());
            return null;
        }
    }
}
