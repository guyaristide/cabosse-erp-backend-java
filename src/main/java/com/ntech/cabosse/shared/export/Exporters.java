package com.ntech.cabosse.shared.export;

import com.ntech.cabosse.shared.exception.BusinessException;
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
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.util.CellRangeAddress;
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
            // Lignes
            for (T row : dataset.rows()) {
                line.setLength(0);
                for (int i = 0; i < dataset.columns().size(); i++) {
                    if (i > 0) line.append(',');
                    Object v = csvSerialize(dataset.columns().get(i).extractor().apply(row));
                    line.append(csvCell(formatForText(v)));
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

    public static <T> void writeXlsx(ExportDataset<T> dataset, OutputStream out) {
        try (Workbook wb = new XSSFWorkbook()) {
            String sheetName = safeSheetName(dataset.title());
            Sheet sheet = wb.createSheet(sheetName);

            // Styles
            CellStyle headerStyle = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            CellStyle dateStyle = wb.createCellStyle();
            DataFormat fmt = wb.createDataFormat();
            dateStyle.setDataFormat(fmt.getFormat("dd/mm/yyyy"));

            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setDataFormat(fmt.getFormat("#,##0.00"));

            // Header row
            Row header = sheet.createRow(0);
            for (int i = 0; i < dataset.columns().size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(dataset.columns().get(i).header());
                cell.setCellStyle(headerStyle);
            }

            // Repérer les colonnes image — elles auront leur largeur figée
            // (POI.autoSizeColumn ne sait pas évaluer les images) et chaque
            // ligne portant une image aura sa hauteur augmentée.
            boolean[] isImageCol = new boolean[dataset.columns().size()];

            // Drawing : créé paresseusement quand on rencontre la 1re image.
            Drawing<?> drawing = null;
            CreationHelper helper = wb.getCreationHelper();

            // Body
            int rowIdx = 1;
            for (T row : dataset.rows()) {
                Row sheetRow = sheet.createRow(rowIdx);
                boolean rowHasImage = false;
                for (int i = 0; i < dataset.columns().size(); i++) {
                    Object v = dataset.columns().get(i).extractor().apply(row);
                    Cell c = sheetRow.createCell(i);
                    if (v instanceof ExportImage img && img.bytes() != null && img.bytes().length > 0) {
                        isImageCol[i] = true;
                        rowHasImage = true;
                        if (drawing == null) drawing = sheet.createDrawingPatriarch();
                        embedXlsxImage(wb, drawing, helper, img, i, rowIdx);
                    } else {
                        applyXlsxValue(c, v instanceof ExportImage ? null : v, dateStyle, numberStyle);
                    }
                }
                if (rowHasImage) sheetRow.setHeightInPoints(48f);
                rowIdx++;
            }

            // Auto-size pour les colonnes non-image. Les images ont une
            // largeur fixe ~10 caractères.
            for (int i = 0; i < dataset.columns().size(); i++) {
                if (isImageCol[i]) {
                    sheet.setColumnWidth(i, 10 * 256);
                } else {
                    sheet.autoSizeColumn(i);
                }
            }
            sheet.createFreezePane(0, 1);

            wb.write(out);
            out.flush();
        } catch (IOException e) {
            throw new BusinessException("Erreur d'écriture XLSX : " + e.getMessage(), e);
        }
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

    private static void applyXlsxValue(org.apache.poi.ss.usermodel.Cell cell, Object v,
                                       CellStyle dateStyle, CellStyle numberStyle) {
        if (v == null) { cell.setBlank(); return; }
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            cell.setCellStyle(numberStyle);
            return;
        }
        if (v instanceof BigDecimal bd) {
            cell.setCellValue(bd.doubleValue());
            cell.setCellStyle(numberStyle);
            return;
        }
        if (v instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        if (v instanceof LocalDate d) {
            cell.setCellValue(Date.from(d.atStartOfDay(UTC).toInstant()));
            cell.setCellStyle(dateStyle);
            return;
        }
        if (v instanceof Instant i) {
            cell.setCellValue(Date.from(i));
            cell.setCellStyle(dateStyle);
            return;
        }
        if (v instanceof Date d) {
            cell.setCellValue(d);
            cell.setCellStyle(dateStyle);
            return;
        }
        cell.setCellValue(v.toString());
    }

    /** Nom d'onglet Excel : max 31 chars, pas de \ / ? * [ ]. */
    private static String safeSheetName(String raw) {
        if (raw == null || raw.isBlank()) return "Export";
        String cleaned = raw.replaceAll("[\\\\/?*\\[\\]]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    // ─── PDF ──────────────────────────────────────────────────────

    public static <T> void writePdf(ExportDataset<T> dataset, OutputStream out) {
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 36, 36);
        try {
            PdfWriter.getInstance(doc, out);
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

            boolean stripe = false;
            for (T row : dataset.rows()) {
                for (ExportColumn<T> col : dataset.columns()) {
                    Object v = col.extractor().apply(row);
                    PdfPCell cell;
                    if (v instanceof ExportImage img
                            && img.bytes() != null && img.bytes().length > 0) {
                        cell = buildPdfImageCell(img);
                    } else {
                        Object printable = v instanceof ExportImage ? null : v;
                        cell = new PdfPCell(new Paragraph(formatForText(printable), cellFont));
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

    private static String formatForText(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal bd) {
            return NumberFormat.getNumberInstance(FR).format(bd);
        }
        if (v instanceof Number n) {
            return NumberFormat.getNumberInstance(FR).format(n);
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
