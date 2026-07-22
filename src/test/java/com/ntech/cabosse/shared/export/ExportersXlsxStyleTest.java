package com.ntech.cabosse.shared.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests purs (pas de Quarkus) de l'export XLSX unifié.
 *
 * <p>Cible le style commun appliqué à tous les exports xlsx du SaaS :
 * en-tête beige + bordure {@code medium}, cellules de données bordées
 * {@code thin}, formats numériques par type de colonne, freeze pane,
 * largeurs proportionnelles, alignements à droite pour les numériques.</p>
 *
 * <p>Le but n'est pas d'inspecter chaque pixel — c'est de figer les
 * invariants importants (couleurs de fond, formats, alignements,
 * détection numérique/date/pct/qty) pour éviter une régression silencieuse.</p>
 */
class ExportersXlsxStyleTest {

    private record DemoRow(
            String reference,
            LocalDate dateCommande,
            BigDecimal totalFcfa,
            BigDecimal quantite,
            BigDecimal tvaPct,
            String fournisseur
    ) {}

    private static ExportDataset<DemoRow> sampleDataset() {
        List<ExportColumn<DemoRow>> cols = List.of(
                ExportColumn.of("Référence",     DemoRow::reference),
                ExportColumn.of("Date commande", DemoRow::dateCommande),
                ExportColumn.of("Total FCFA",    DemoRow::totalFcfa),
                ExportColumn.of("Quantité",      DemoRow::quantite),
                ExportColumn.of("TVA (%)",       DemoRow::tvaPct),
                ExportColumn.of("Fournisseur",   DemoRow::fournisseur)
        );
        List<DemoRow> rows = List.of(
                new DemoRow("BC-001", LocalDate.of(2026, 5, 31),
                        new BigDecimal("125000"), new BigDecimal("12.5"),
                        new BigDecimal("18"), "Cacao Plus SARL"),
                new DemoRow("BC-002", LocalDate.of(2026, 5, 30),
                        new BigDecimal("75500"), new BigDecimal("3"),
                        new BigDecimal("18"), "Karité Côte SARL")
        );
        return new ExportDataset<>("Bons de commande", cols, rows);
    }

    private static byte[] writeBytes(ExportDataset<?> ds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Exporters.write(ExportFormat.XLSX, ds, out);
        return out.toByteArray();
    }

    /** Le fichier produit s'ouvre sans erreur et contient bien une feuille. */
    @Test
    void shouldProduceReadableWorkbookWithOneSheet() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("Bons de commande");
        }
    }

    /**
     * Un titre contenant un caractère interdit par POI dans un nom de feuille
     * (ici « : », cf. l'export « Ventes : lignes ») ne doit pas faire planter
     * l'export : le nom est nettoyé, le fichier reste lisible.
     */
    @Test
    void shouldSanitizeSheetNameWithForbiddenChars() throws Exception {
        ExportDataset<DemoRow> ds = new ExportDataset<>(
                "Ventes : lignes / détails [2026]?*", sampleDataset().columns(), sampleDataset().rows());
        byte[] bytes = writeBytes(ds);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            String name = wb.getSheetAt(0).getSheetName();
            assertThat(name).doesNotContain(":", "\\", "/", "?", "*", "[", "]");
            assertThat(name.length()).isLessThanOrEqualTo(31);
            assertThat(name).isNotBlank();
        }
    }

    /** L'en-tête porte le fond beige {@code #EAE5DA} et un texte gras 11 pt. */
    @Test
    void shouldStyleHeaderRowWithBeigeBackgroundAndBoldFont() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getHeightInPoints()).isEqualTo(32f);

            XSSFCellStyle style = (XSSFCellStyle) header.getCell(0).getCellStyle();
            assertThat(style.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
            XSSFColor bg = style.getFillForegroundColorColor();
            assertThat(bg).isNotNull();
            byte[] rgb = bg.getRGB();
            assertThat(rgb).containsExactly((byte) 0xEA, (byte) 0xE5, (byte) 0xDA);

            assertThat(style.getFont().getBold()).isTrue();
            assertThat(style.getFont().getFontHeightInPoints()).isEqualTo((short) 11);
            assertThat(style.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            assertThat(style.getVerticalAlignment()).isEqualTo(VerticalAlignment.CENTER);
            assertThat(style.getBorderBottom()).isEqualTo(BorderStyle.MEDIUM);
            assertThat(style.getBorderTop()).isEqualTo(BorderStyle.THIN);
        }
    }

    /** Les cellules de données ont des bordures fines sur les 4 côtés et hauteur 22 pt. */
    @Test
    void shouldStyleDataCellsWithThinBordersAndStandardHeight() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row row = sheet.getRow(1);
            assertThat(row.getHeightInPoints()).isEqualTo(22f);
            CellStyle st = row.getCell(0).getCellStyle();
            assertThat(st.getBorderTop()).isEqualTo(BorderStyle.THIN);
            assertThat(st.getBorderBottom()).isEqualTo(BorderStyle.THIN);
            assertThat(st.getBorderLeft()).isEqualTo(BorderStyle.THIN);
            assertThat(st.getBorderRight()).isEqualTo(BorderStyle.THIN);
        }
    }

    /** Colonne « Total FCFA » → format {@code #,##0} + alignement à droite. */
    @Test
    void shouldFormatMoneyColumnAsThousandsAndAlignRight() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle st = sheet.getRow(1).getCell(2).getCellStyle(); // Total FCFA
            assertThat(st.getDataFormatString()).isEqualTo("#,##0");
            assertThat(st.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(125000.0);
        }
    }

    /** Colonne « Quantité » → format avec décimales optionnelles. */
    @Test
    void shouldFormatQuantityColumnWithOptionalDecimals() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle st = sheet.getRow(1).getCell(3).getCellStyle();
            assertThat(st.getDataFormatString()).isEqualTo("#,##0.##");
            assertThat(st.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        }
    }

    /** Colonne « TVA (%) » → format pourcentage suffixé. */
    @Test
    void shouldFormatPercentageColumnAsPctSuffix() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle st = sheet.getRow(1).getCell(4).getCellStyle();
            assertThat(st.getDataFormatString()).isEqualTo("0.0\" %\"");
            assertThat(st.getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        }
    }

    /** Colonne « Date commande » → format JJ/MM/AAAA + alignement centre. */
    @Test
    void shouldFormatDateColumnAsFrenchShortDate() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle st = sheet.getRow(1).getCell(1).getCellStyle();
            assertThat(st.getDataFormatString()).isEqualTo("dd/mm/yyyy");
            assertThat(st.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
        }
    }

    /** Colonne texte (« Fournisseur ») → alignement centre, pas de format numérique. */
    @Test
    void shouldKeepTextColumnCenteredWithoutNumericFormat() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle st = sheet.getRow(1).getCell(5).getCellStyle();
            assertThat(st.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
            // Format "General" attendu (pas de #,##0 ni dd/mm/yyyy)
            String fmt = st.getDataFormatString();
            assertThat(fmt).doesNotContain("#,##0");
            assertThat(fmt).doesNotContain("dd/mm/yyyy");
        }
    }

    /** Freeze pane sur la 1re ligne. */
    @Test
    void shouldFreezeFirstRow() throws Exception {
        byte[] bytes = writeBytes(sampleDataset());
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPaneInformation()).isNotNull();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        }
    }

    /** Valeur {@code null} → cellule vide mais le style de colonne reste posé. */
    @Test
    void shouldHandleNullValuesGracefully() throws Exception {
        DemoRow rowWithNulls = new DemoRow(null, null, null, null, null, null);
        ExportDataset<DemoRow> ds = new ExportDataset<>("Nuls",
                sampleDataset().columns(), List.of(rowWithNulls));

        byte[] bytes = writeBytes(ds);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row row = sheet.getRow(1);
            for (int i = 0; i < 6; i++) {
                assertThat(row.getCell(i).getCellType()).isEqualTo(CellType.BLANK);
                // Bordures appliquées même sur les cellules vides
                assertThat(row.getCell(i).getCellStyle().getBorderTop()).isEqualTo(BorderStyle.THIN);
            }
        }
    }

    /** Aucune ligne du tout : header présent, format de colonne dérivé du nom. */
    @Test
    void shouldStillStyleColumnsWhenDatasetHasNoRows() throws Exception {
        ExportDataset<DemoRow> ds = new ExportDataset<>(
                "Vide",
                sampleDataset().columns(),
                List.of()
        );
        byte[] bytes = writeBytes(ds);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0)).isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(0);
        }
    }
}
