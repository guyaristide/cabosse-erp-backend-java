package com.ntech.cabosse.shared.export;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * Sérialiseurs partagés (CSV, XLSX, PDF) d'un {@link ExportDataset}
 * vers un {@link OutputStream}. Aucune logique métier ici — les pages
 * spécifiques décident des colonnes via {@link ExportColumn}.
 */
public final class Exporters {

    private Exporters() {}

    private static final Locale FR = Locale.forLanguageTag("fr-FR");
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy", FR);
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** Choisit le bon writer selon le format et écrit dans le stream donné. */
    public static <T> void write(ExportFormat format, ExportDataset<T> dataset, OutputStream out) {
        switch (format) {
            case CSV -> writeCsv(dataset, out);
            case XLSX -> writeXlsx(dataset, out);
            case PDF -> writePdf(dataset, out);
            case META -> writeMeta(dataset, out);
        }
    }

    // ─── Méta : les colonnes proposées, sans les données ───────────

    /**
     * Colonnes proposables au sélecteur.
     *
     * <p>{@code columns} garde les libellés seuls : c'est ce qu'un front
     * plus ancien attend, et le retirer casserait son affichage. La liste
     * {@code columnDefs} porte le couple clé et libellé, que le front
     * renvoie ensuite par clé. Les deux disparaîtront en une, une fois les
     * deux côtés alignés.</p>
     */
    private static <T> void writeMeta(ExportDataset<T> dataset, OutputStream out) {
        try {
            var columns = dataset.columns().stream().map(ExportColumn::header).toList();
            var defs = dataset.columns().stream()
                    .map(c -> java.util.Map.of("key", c.key(), "header", c.header()))
                    .toList();
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValue(out, java.util.Map.of("columns", columns, "columnDefs", defs));
        } catch (IOException e) {
            throw new BusinessException("Erreur d'écriture des métadonnées : " + e.getMessage(), e);
        }
    }

    // ─── CSV ──────────────────────────────────────────────────────

    public static <T> void writeCsv(ExportDataset<T> dataset, OutputStream out) {
        try {
            // BOM UTF-8 pour qu'Excel sache lire les accents directement.
            out.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
            // En-têtes
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < dataset.columns().size(); i++) {
                if (i > 0) line.append(',');
                line.append(csvCell(dataset.columns().get(i).header()));
            }
            line.append("\r\n");
            out.write(line.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Natures des colonnes : en CSV le texte écrit EST la donnée,
            // le nombre de décimales n'y est pas qu'une question d'affichage.
            ColumnKind[] kinds = detectColumnKinds(dataset);
            // Lignes
            for (T row : dataset.rows()) {
                line.setLength(0);
                for (int i = 0; i < dataset.columns().size(); i++) {
                    if (i > 0) line.append(',');
                    Object v = csvSerialize(dataset.columns().get(i).extractor().apply(row));
                    line.append(csvCell(formatForText(v, kinds[i])));
                }
                line.append("\r\n");
                out.write(line.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            out.flush();
        } catch (IOException e) {
            throw new BusinessException("Erreur d'écriture CSV : " + e.getMessage(), e);
        }
    }

    private static Object csvSerialize(Object v) {
        if (v instanceof ExportImage) return ""; // CSV ne porte pas d'image
        return v;
    }

    /** Échappe une cellule CSV (guillemets autour si , " ou \n présents). */
    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    // ─── XLSX ─────────────────────────────────────────────────────

    /**
     * Style cible (uniforme tous exports xlsx, aligné sur les templates
     * d'import frontend ExcelJS) :
     * <ul>
     *   <li>En-tête : fond beige {@code #EAE5DA}, texte {@code #1A1A1A} gras 11 pt,
     *       centré, hauteur 32 pt, bordure basse {@code medium} {@code #B8AE99},
     *       les trois autres côtés en {@code thin} {@code #B8AE99}.</li>
     *   <li>Cellules : bordures {@code thin} {@code #D4CFC4}, alignement vertical centre,
     *       horizontal centre (texte) ou droite (numérique), hauteur 22 pt.</li>
     *   <li>Formats colonne par type détecté : argent FCFA {@code #,##0}, qty
     *       {@code #,##0.##}, pourcentage {@code 0.0"%"}, dates {@code dd/mm/yyyy}.</li>
     *   <li>Freeze pane sur la 1ʳᵉ ligne. Largeurs ≈ {@code max(label.length, valeur.length)+2}.</li>
     * </ul>
     *
     * <p>Stratégie de typage des colonnes :</p>
     * <ol>
     *   <li>Première passe sur toutes les lignes pour détecter le type Java
     *       dominant (Number/BigDecimal/Date/LocalDate/Instant/texte).</li>
     *   <li>Pour les colonnes numériques, sous-type (money / qty / pct)
     *       déterminé par heuristique sur l'en-tête. Money est le défaut.</li>
     * </ol>
     */
    public static <T> void writeXlsx(ExportDataset<T> dataset, OutputStream out) {
        try (Workbook wb = new XSSFWorkbook()) {
            String sheetName = safeSheetName(dataset.title());
            Sheet sheet = wb.createSheet(sheetName);
            DataFormat fmt = wb.createDataFormat();
            CreationHelper helper = wb.getCreationHelper();

            final int colCount = dataset.columns().size();

            // ─── Détection des types de colonnes (1re passe non destructive) ─
            ColumnKind[] kinds = detectColumnKinds(dataset);
            boolean[] isImageCol = new boolean[colCount];

            // ─── Styles partagés ──────────────────────────────────────────
            CellStyle headerStyle = buildHeaderStyle(wb);
            // Styles de données par couple (kind, isFirst column? non — couleur de bordure unique).
            // Un style par sous-type suffit : on les construit paresseusement.
            CellStyle textStyle  = buildBodyStyle(wb, fmt, ColumnKind.TEXT);
            CellStyle moneyStyle = buildBodyStyle(wb, fmt, ColumnKind.NUMBER_MONEY);
            CellStyle qtyStyle   = buildBodyStyle(wb, fmt, ColumnKind.NUMBER_QTY);
            CellStyle preciseStyle = buildBodyStyle(wb, fmt, ColumnKind.NUMBER_PRECISE);
            CellStyle pctStyle   = buildBodyStyle(wb, fmt, ColumnKind.NUMBER_PCT);
            CellStyle dateStyle  = buildBodyStyle(wb, fmt, ColumnKind.DATE);

            // ─── Ligne d'en-tête ──────────────────────────────────────────
            Row header = sheet.createRow(0);
            header.setHeightInPoints(32f);
            for (int i = 0; i < colCount; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(dataset.columns().get(i).header());
                cell.setCellStyle(headerStyle);
            }

            // ─── Corps : écriture des lignes + ancrage d'éventuelles images ─
            Drawing<?> drawing = null;
            int[] maxLen = new int[colCount];
            for (int i = 0; i < colCount; i++) {
                maxLen[i] = lengthOf(dataset.columns().get(i).header());
            }

            int rowIdx = 1;
            for (T row : dataset.rows()) {
                Row sheetRow = sheet.createRow(rowIdx);
                sheetRow.setHeightInPoints(22f);
                boolean rowHasImage = false;
                for (int i = 0; i < colCount; i++) {
                    Object v = dataset.columns().get(i).extractor().apply(row);
                    Cell c = sheetRow.createCell(i);
                    if (v instanceof ExportImage img && img.bytes() != null && img.bytes().length > 0) {
                        isImageCol[i] = true;
                        rowHasImage = true;
                        if (drawing == null) drawing = sheet.createDrawingPatriarch();
                        embedXlsxImage(wb, drawing, helper, img, i, rowIdx);
                        // Cellule support stylée pour conserver bordures même sans valeur.
                        c.setCellStyle(textStyle);
                    } else {
                        Object printable = v instanceof ExportImage ? null : v;
                        CellStyle cs = switch (kinds[i]) {
                            case NUMBER_MONEY   -> moneyStyle;
                            case NUMBER_QTY     -> qtyStyle;
                            case NUMBER_PRECISE -> preciseStyle;
                            case NUMBER_PCT     -> pctStyle;
                            case DATE           -> dateStyle;
                            case TEXT           -> textStyle;
                        };
                        applyXlsxValue(c, printable, cs);
                        int len = printableLength(printable);
                        if (len > maxLen[i]) maxLen[i] = len;
                    }
                }
                if (rowHasImage) sheetRow.setHeightInPoints(48f);
                rowIdx++;
            }

            // ─── Largeurs de colonne (heuristique : longueur+2, bornée) ────
            // Les images : largeur fixe ~10 chars. Sinon : maxLen+2 clampé
            // entre 8 et 60 pour éviter les colonnes ridicules ou géantes.
            for (int i = 0; i < colCount; i++) {
                if (isImageCol[i]) {
                    sheet.setColumnWidth(i, 10 * 256);
                } else {
                    int chars = Math.max(8, Math.min(60, maxLen[i] + 2));
                    sheet.setColumnWidth(i, chars * 256);
                }
            }
            sheet.createFreezePane(0, 1);

            wb.write(out);
            out.flush();
        } catch (IOException e) {
            throw new BusinessException("Erreur d'écriture XLSX : " + e.getMessage(), e);
        }
    }

    /** Catégorie de format appliquée au niveau colonne. */
    // ColumnKind est désormais un type partagé du module : une colonne
    // peut déclarer sa nature, le writer applique la même.

    /**
     * Détermine la nature de chaque colonne. Approche prudente :
     * <ul>
     *   <li>1re passe : type Java dominant des valeurs non-{@code null}
     *       (un seul {@link Number}/{@link BigDecimal} suffit à classer
     *       la colonne en numérique ; une date suffit pour la classer date).
     *       En cas de conflit (numérique + texte), texte gagne.</li>
     *   <li>Sous-type numérique : heuristique sur l'en-tête.
     *       Mots-clés « % », « pct », « tva », « taux », « marge », « pourcent »
     *       → {@link ColumnKind#NUMBER_PCT}.
     *       Mots-clés « qté », « quantité », « qty », « seuil », « stock »
     *       → {@link ColumnKind#NUMBER_QTY}.
     *       Tout le reste → {@link ColumnKind#NUMBER_MONEY} (cas dominant, FCFA).</li>
     * </ul>
     */
    private static <T> ColumnKind[] detectColumnKinds(ExportDataset<T> dataset) {
        int n = dataset.columns().size();
        ColumnKind[] kinds = new ColumnKind[n];
        // Une colonne qui déclare sa nature n'est pas devinée : le code
        // sait ce qu'il produit, la déduction n'est qu'un repli pour les
        // colonnes qui ne le disent pas encore.
        boolean[] declared = new boolean[n];
        for (int i = 0; i < n; i++) {
            ColumnKind k = dataset.columns().get(i).kind();
            if (k != null) { kinds[i] = k; declared[i] = true; }
        }
        boolean[] sawNumber = new boolean[n];
        boolean[] sawDate   = new boolean[n];
        boolean[] sawText   = new boolean[n];

        for (T row : dataset.rows()) {
            for (int i = 0; i < n; i++) {
                Object v = dataset.columns().get(i).extractor().apply(row);
                if (v == null) continue;
                if (v instanceof ExportImage) continue;
                if (v instanceof Number || v instanceof BigDecimal) {
                    sawNumber[i] = true;
                } else if (v instanceof LocalDate || v instanceof Instant || v instanceof Date) {
                    sawDate[i] = true;
                } else if (v instanceof Boolean) {
                    sawText[i] = true;
                } else {
                    sawText[i] = true;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            if (declared[i]) continue;   // la colonne a dit ce qu'elle est
            String header = dataset.columns().get(i).header();
            if (sawText[i]) {
                kinds[i] = ColumnKind.TEXT;            // texte gagne en cas de conflit
            } else if (sawDate[i]) {
                kinds[i] = ColumnKind.DATE;
            } else if (sawNumber[i]) {
                kinds[i] = numericKindFromHeader(header);
            } else {
                kinds[i] = headerOnlyKind(header);     // colonne vide : tente l'en-tête
            }
        }
        return kinds;
    }

    /** Pour une colonne entièrement vide, on regarde uniquement l'en-tête. */
    private static ColumnKind headerOnlyKind(String header) {
        if (header == null) return ColumnKind.TEXT;
        String h = header.toLowerCase(FR);
        if (looksLikeDate(h))    return ColumnKind.DATE;
        if (looksLikePct(h))     return ColumnKind.NUMBER_PCT;
        if (looksLikeQty(h))     return ColumnKind.NUMBER_QTY;
        if (looksLikeMoney(h))   return ColumnKind.NUMBER_MONEY;
        return ColumnKind.TEXT;
    }

    private static ColumnKind numericKindFromHeader(String header) {
        if (header == null) return ColumnKind.NUMBER_MONEY;
        String h = header.toLowerCase(FR);
        if (looksLikePct(h)) return ColumnKind.NUMBER_PCT;
        if (looksLikeQty(h)) return ColumnKind.NUMBER_QTY;
        return ColumnKind.NUMBER_MONEY;
    }

    private static boolean looksLikePct(String h) {
        return h.contains("%")
                || h.contains("pct")
                || h.contains("pourcent")
                || h.contains("taux")
                || h.contains("tva")
                || h.contains("marge")
                || h.contains("remise");
    }

    private static boolean looksLikeQty(String h) {
        return h.contains("qté")
                || h.contains("qte")
                || h.contains("quantité")
                || h.contains("quantite")
                || h.contains("qty")
                || h.contains("seuil")
                || h.contains("stock")
                || h.contains("nombre")
                || h.contains("nb ");
    }

    private static boolean looksLikeMoney(String h) {
        return h.contains("fcfa")
                || h.contains("xof")
                || h.contains("montant")
                || h.contains("total")
                || h.contains("prix")
                || h.contains("coût")
                || h.contains("cout")
                || h.contains("cmup")
                || h.contains("solde")
                || h.contains("payé")
                || h.contains("paye");
    }

    private static boolean looksLikeDate(String h) {
        return h.contains("date") || h.contains("échéance") || h.contains("echeance")
                || h.contains("créé le") || h.contains("cree le") || h.contains("modifié le");
    }

    /** Style en-tête : voir Javadoc de {@link #writeXlsx}. */
    private static CellStyle buildHeaderStyle(Workbook wb) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        XSSFFont font = (XSSFFont) wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(new XSSFColor(hex(0x1A, 0x1A, 0x1A), null));
        s.setFont(font);
        s.setFillForegroundColor(new XSSFColor(hex(0xEA, 0xE5, 0xDA), null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        XSSFColor borderColor = new XSSFColor(hex(0xB8, 0xAE, 0x99), null);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.MEDIUM);
        s.setTopBorderColor(borderColor);
        s.setLeftBorderColor(borderColor);
        s.setRightBorderColor(borderColor);
        s.setBottomBorderColor(borderColor);
        return s;
    }

    /** Style cellule de données pour une catégorie. */
    private static CellStyle buildBodyStyle(Workbook wb, DataFormat fmt, ColumnKind kind) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        switch (kind) {
            case NUMBER_MONEY -> {
                s.setDataFormat(fmt.getFormat("#,##0"));
                s.setAlignment(HorizontalAlignment.RIGHT);
            }
            case NUMBER_QTY -> {
                s.setDataFormat(fmt.getFormat("#,##0.##"));
                s.setAlignment(HorizontalAlignment.RIGHT);
            }
            case NUMBER_PRECISE -> {
                // Assez de décimales pour une coordonnée : trois seulement
                // situent une parcelle à une centaine de mètres près.
                s.setDataFormat(fmt.getFormat("0.######"));
                s.setAlignment(HorizontalAlignment.RIGHT);
            }
            case NUMBER_PCT -> {
                // Valeurs stockées en pourcentage entier (18 pour 18 %) →
                // suffixe " %" littéral plutôt que le format de Excel qui
                // multiplierait par 100. Cohérent avec les templates d'import.
                s.setDataFormat(fmt.getFormat("0.0\" %\""));
                s.setAlignment(HorizontalAlignment.RIGHT);
            }
            case DATE -> {
                s.setDataFormat(fmt.getFormat("dd/mm/yyyy"));
                s.setAlignment(HorizontalAlignment.CENTER);
            }
            case TEXT -> {
                s.setAlignment(HorizontalAlignment.CENTER);
                s.setWrapText(true);
            }
        }
        XSSFColor borderColor = new XSSFColor(hex(0xD4, 0xCF, 0xC4), null);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setTopBorderColor(borderColor);
        s.setLeftBorderColor(borderColor);
        s.setRightBorderColor(borderColor);
        s.setBottomBorderColor(borderColor);
        return s;
    }

    private static byte[] hex(int r, int g, int b) {
        return new byte[] { (byte) r, (byte) g, (byte) b };
    }

    /** Longueur visuelle de l'objet une fois formaté texte (heuristique largeur). */
    private static int printableLength(Object v) {
        return lengthOf(formatForText(v));
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    /** Embarque une image dans une cellule Excel (ancrage cellule unique). */
    private static void embedXlsxImage(Workbook wb, Drawing<?> drawing, CreationHelper helper,
                                       ExportImage img, int col, int row) {
        int format = switch (img.mimeType() == null ? "" : img.mimeType().toLowerCase()) {
            case "image/jpeg", "image/jpg" -> Workbook.PICTURE_TYPE_JPEG;
            case "image/png"               -> Workbook.PICTURE_TYPE_PNG;
            default                        -> Workbook.PICTURE_TYPE_PNG; // tentative best-effort
        };
        int pictureIdx = wb.addPicture(img.bytes(), format);
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(col);
        anchor.setRow1(row);
        anchor.setCol2(col + 1);
        anchor.setRow2(row + 1);
        // Petites marges pour ne pas coller aux bords de la cellule.
        anchor.setDx1(2 * 9525); anchor.setDy1(2 * 9525);
        anchor.setDx2(-2 * 9525); anchor.setDy2(-2 * 9525);
        Picture pic = drawing.createPicture(anchor, pictureIdx);
        // Ne pas appeler pic.resize() — il écraserait notre ancrage cellule
        // et l'image dépasserait sur les lignes voisines pour les originaux
        // de grande taille. L'ancrage déjà posé maintient l'image dans la cellule.
        @SuppressWarnings("unused") Object _ignore = pic;
    }

    /**
     * Pose la valeur dans la cellule en respectant son type Java, et applique
     * le style de colonne pré-calculé. Le style porte déjà l'alignement, les
     * bordures, et le format (#,##0 / dd/mm/yyyy / …) ; on n'a plus à
     * basculer entre styles ici.
     *
     * <p>Cas {@code null} : la cellule reste vide (mais reçoit quand même
     * le style, sinon les bordures de la grille seraient cassées sur la
     * cellule absente).</p>
     */
    private static void applyXlsxValue(Cell cell, Object v, CellStyle style) {
        cell.setCellStyle(style);
        if (v == null) { cell.setBlank(); return; }
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return;
        }
        if (v instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            return;
        }
        if (v instanceof Boolean b) {
            cell.setCellValue(b ? "Oui" : "Non");
            return;
        }
        if (v instanceof LocalDate d) {
            cell.setCellValue(Date.from(d.atStartOfDay(UTC).toInstant()));
            return;
        }
        if (v instanceof Instant i) {
            cell.setCellValue(Date.from(i));
            return;
        }
        if (v instanceof Date d) {
            cell.setCellValue(d);
            return;
        }
        cell.setCellValue(v.toString());
    }

    /** Nom d'onglet Excel : max 31 chars, pas de \ / ? * [ ]. */
    private static String safeSheetName(String raw) {
        if (raw == null || raw.isBlank()) return "Export";
        // Caractères interdits par Apache POI dans un nom de feuille : \ / ? * [ ] :
        String cleaned = raw.replaceAll("[\\\\/?*\\[\\]:]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) return "Export";
        return cleaned.length() > 31 ? cleaned.substring(0, 31).trim() : cleaned;
    }

    // ─── PDF ──────────────────────────────────────────────────────

    public static <T> void writePdf(ExportDataset<T> dataset, OutputStream out) {
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 36, 36);
        try {
            PdfWriter.getInstance(doc, out).setPageEvent(new PdfBranding());
            doc.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, java.awt.Color.BLACK);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, java.awt.Color.DARK_GRAY);

            Paragraph title = new Paragraph(dataset.title(), titleFont);
            title.setSpacingAfter(2f);
            doc.add(title);

            Paragraph subtitle = new Paragraph(
                    "Export du " + DATE_FR.format(LocalDate.now())
                            + " · " + dataset.rows().size() + " ligne"
                            + (dataset.rows().size() > 1 ? "s" : ""),
                    small
            );
            subtitle.setSpacingAfter(10f);
            doc.add(subtitle);

            PdfPTable table = new PdfPTable(dataset.columns().size());
            table.setWidthPercentage(100);
            table.setHeaderRows(1);

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, java.awt.Color.WHITE);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, java.awt.Color.BLACK);

            for (ExportColumn<T> col : dataset.columns()) {
                PdfPCell header = new PdfPCell(new Paragraph(col.header(), headerFont));
                header.setBackgroundColor(new java.awt.Color(40, 40, 40));
                header.setPadding(6f);
                header.setBorderColor(java.awt.Color.GRAY);
                table.addCell(header);
            }

            // Comme en CSV : le texte imprimé est la donnée que le lecteur
            // recopiera, sa précision doit suivre la nature de la colonne.
            ColumnKind[] pdfKinds = detectColumnKinds(dataset);
            boolean stripe = false;
            for (T row : dataset.rows()) {
                for (int ci = 0; ci < dataset.columns().size(); ci++) {
                    ExportColumn<T> col = dataset.columns().get(ci);
                    Object v = col.extractor().apply(row);
                    PdfPCell cell;
                    if (v instanceof ExportImage img
                            && img.bytes() != null && img.bytes().length > 0) {
                        cell = buildPdfImageCell(img);
                    } else {
                        Object printable = v instanceof ExportImage ? null : v;
                        cell = new PdfPCell(new Paragraph(
                                formatForText(printable, pdfKinds[ci]), cellFont));
                    }
                    cell.setPadding(5f);
                    cell.setBorderColor(java.awt.Color.LIGHT_GRAY);
                    if (stripe) cell.setBackgroundColor(new java.awt.Color(248, 248, 248));
                    if (v instanceof Number || v instanceof BigDecimal) {
                        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    table.addCell(cell);
                }
                stripe = !stripe;
            }

            doc.add(table);
        } catch (Exception e) {
            throw new BusinessException("Erreur d'écriture PDF : " + e.getMessage(), e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
    }

    /**
     * Construit une cellule PDF avec image redimensionnée pour tenir dans
     * un thumb ~36 pt (proche d'un 48 px en sortie). Renvoie une cellule
     * vide si l'image ne se décode pas (rare — fallback silencieux).
     */
    private static PdfPCell buildPdfImageCell(ExportImage img) {
        try {
            Image pdfImg = Image.getInstance(img.bytes());
            pdfImg.scaleToFit(36f, 36f);
            PdfPCell cell = new PdfPCell(pdfImg, true);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setFixedHeight(44f);
            return cell;
        } catch (Exception e) {
            // Image illisible : fallback vide pour ne pas tuer l'export.
            return new PdfPCell();
        }
    }

    // ─── Formatage texte commun (CSV + PDF) ──────────────────────

    /** Formateur numérique correspondant à la nature de la colonne. */
    private static NumberFormat numberFormatFor(ColumnKind kind) {
        NumberFormat nf = NumberFormat.getNumberInstance(FR);
        if (kind == ColumnKind.NUMBER_MONEY) {
            nf.setMaximumFractionDigits(0);
        } else if (kind == ColumnKind.NUMBER_PRECISE) {
            nf.setMaximumFractionDigits(6);
        } else if (kind == ColumnKind.NUMBER_QTY || kind == ColumnKind.NUMBER_PCT) {
            nf.setMaximumFractionDigits(3);
        }
        return nf;
    }

    private static String formatForText(Object v) {
        return formatForText(v, null);
    }

    /**
     * Rendu texte pour le CSV et le PDF.
     *
     * <p>La nature de la colonne compte ici autant qu'en Excel : le format
     * d'un classeur n'est qu'un affichage, la valeur exacte y reste, alors
     * qu'en CSV et en PDF <em>c'est ce texte qui est la donnée</em>. Sans
     * cette distinction, le formateur par défaut coupait à trois décimales
     * et une coordonnée perdait une centaine de mètres à chaque export.</p>
     */
    private static String formatForText(Object v, ColumnKind kind) {
        if (v == null) return "";
        if (v instanceof BigDecimal || v instanceof Number) {
            return numberFormatFor(kind).format(v);
        }
        if (v instanceof LocalDate d) {
            return DATE_FR.format(d);
        }
        if (v instanceof Instant i) {
            return DATE_FR.format(i.atZone(UTC).toLocalDate());
        }
        if (v instanceof Boolean b) {
            return b ? "Oui" : "Non";
        }
        return v.toString();
    }
}
