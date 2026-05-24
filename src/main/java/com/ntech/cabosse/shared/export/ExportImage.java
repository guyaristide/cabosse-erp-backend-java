package com.ntech.cabosse.shared.export;

/**
 * Valeur de cellule d'export portant un binaire image — embarqué tel
 * quel dans les formats qui le supportent (XLSX et PDF). En CSV, la
 * cellule est laissée vide.
 *
 * <p>L'extracteur d'une colonne image renvoie {@code null} si l'entité
 * n'a pas d'image (ou si la récupération échoue) — la cellule est alors
 * vide dans tous les formats.</p>
 *
 * @param bytes    binaire complet (PNG / JPEG / WebP — tels qu'uploadés)
 * @param mimeType type MIME du binaire
 */
public record ExportImage(byte[] bytes, String mimeType) {}
