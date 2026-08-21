package com.ntech.cabosse.shared.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L'habillage de marque ne doit jamais faire échouer un export : le PDF
 * se génère avec le filigrane et le pied wordmark, y compris sur un
 * document multi-pages. Le fichier produit sert aussi de support au
 * contrôle visuel (build/branding-sample.pdf).
 */
class PdfBrandingTest {

    record Ligne(int n, String libelle) {}

    @Test
    void a_branded_export_still_renders() throws Exception {
        List<ExportColumn<Ligne>> columns = List.of(
                ExportColumn.of("N°", Ligne::n),
                ExportColumn.of("Libellé", Ligne::libelle),
                ExportColumn.of("Montant", l -> new java.math.BigDecimal(l.n() * 1250)));
        // Assez de lignes pour forcer plusieurs pages : l'événement doit
        // se rejouer sur chacune sans corrompre l'état des images.
        List<Ligne> rows = java.util.stream.IntStream.rangeClosed(1, 120)
                .mapToObj(i -> new Ligne(i, "Opération de contrôle " + i)).toList();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Exporters.writePdf(new ExportDataset<>("Contrôle habillage", columns, rows), out);

        byte[] pdf = out.toByteArray();
        assertTrue(pdf.length > 20_000, "PDF anormalement petit : " + pdf.length);
        assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF"));

        Path sample = Path.of("build", "branding-sample.pdf");
        Files.createDirectories(sample.getParent());
        Files.write(sample, pdf);
    }
}
