package com.ntech.cabosse.shared.export;

import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import java.io.IOException;

/**
 * Habillage de marque de tout PDF produit par la plateforme.
 *
 * <p>Chaque page reçoit deux éléments, dessinés sous le contenu :
 * l'emblème en filigrane, très grand et très peu visible, centré ; et le
 * wordmark en pied de page, petit et discret. Les images viennent de
 * {@code resources/branding/} (l'emblème est celui du favicon, le wordmark
 * celui de la charte d'août 2026).</p>
 *
 * <p>Usage : {@code PdfWriter.getInstance(doc, out).setPageEvent(new PdfBranding())}
 * avant {@code doc.open()}. Une instance par document : l'événement est
 * rappelé à chaque page, y compris sur les documents multi-pages.</p>
 */
public final class PdfBranding extends PdfPageEventHelper {

    private static final float WATERMARK_OPACITY = 0.04f;
    private static final byte[] EMBLEM_BYTES = load("/branding/embleme.png");
    private static final byte[] WORDMARK_BYTES = load("/branding/wordmark.png");

    /** Instances par document : OpenPDF mutile position et échelle sur l'objet Image. */
    private final Image emblem;
    private final Image wordmark;

    public PdfBranding() {
        try {
            this.emblem = Image.getInstance(EMBLEM_BYTES);
            this.wordmark = Image.getInstance(WORDMARK_BYTES);
        } catch (IOException e) {
            throw new IllegalStateException("Images de marque PDF illisibles", e);
        }
    }

    @Override
    public void onEndPage(PdfWriter writer, Document doc) {
        Rectangle page = doc.getPageSize();
        PdfContentByte under = writer.getDirectContentUnder();
        try {
            // ─── Filigrane : l'emblème centré, très grand, à peine visible ───
            under.saveState();
            PdfGState faint = new PdfGState();
            faint.setFillOpacity(WATERMARK_OPACITY);
            faint.setStrokeOpacity(WATERMARK_OPACITY);
            under.setGState(faint);
            float side = Math.min(page.getWidth(), page.getHeight()) * 0.62f;
            emblem.scaleToFit(side, side);
            emblem.setAbsolutePosition(
                    (page.getWidth() - emblem.getScaledWidth()) / 2f,
                    (page.getHeight() - emblem.getScaledHeight()) / 2f);
            under.addImage(emblem);
            under.restoreState();

            // ─── Pied de page : wordmark discret dans la marge basse ───
            float footerWidth = Math.min(58f, page.getWidth() * 0.16f);
            wordmark.scaleToFit(footerWidth, 20f);
            wordmark.setAbsolutePosition(
                    doc.leftMargin(),
                    Math.max(6f, (doc.bottomMargin() - wordmark.getScaledHeight()) / 2f));
            under.saveState();
            PdfGState soft = new PdfGState();
            soft.setFillOpacity(0.75f);
            under.setGState(soft);
            under.addImage(wordmark);
            under.restoreState();
        } catch (Exception e) {
            // L'habillage ne doit jamais faire échouer un export : une page
            // sans filigrane vaut mieux qu'un document refusé.
        }
    }

    private static byte[] load(String path) {
        try (var in = PdfBranding.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Ressource absente : " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Ressource illisible : " + path, e);
        }
    }
}
