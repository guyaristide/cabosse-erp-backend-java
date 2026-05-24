package com.ntech.cabosse.shared.export;

/**
 * Formats d'export supportés. La valeur de l'enum est utilisée à la fois
 * comme code query string ({@code ?format=csv}) et comme extension de
 * fichier ({@code articles-2026-05-20.csv}).
 */
public enum ExportFormat {

    CSV("csv", "text/csv; charset=utf-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf");

    private final String extension;
    private final String mimeType;

    ExportFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String extension() { return extension; }
    public String mimeType() { return mimeType; }

    /**
     * Parsing permissif depuis une chaîne (query string). Renvoie
     * {@link #CSV} par défaut si la valeur est absente ou inconnue —
     * choix conservateur, CSV s'ouvre partout.
     */
    public static ExportFormat parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) return CSV;
        try {
            return ExportFormat.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CSV;
        }
    }
}
