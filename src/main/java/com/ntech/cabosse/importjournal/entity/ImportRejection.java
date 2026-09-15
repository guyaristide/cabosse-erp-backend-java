package com.ntech.cabosse.importjournal.entity;

/**
 * Une ligne que l'import a refusée, et pourquoi.
 *
 * <p>Le numéro est celui du tableur, tel que l'opérateur le lit dans sa
 * feuille : c'est par là qu'il ouvrira son fichier pour corriger. Le
 * motif est celui rendu à l'écran, mot pour mot, pour qu'on relise plus
 * tard ce que la personne a effectivement vu.</p>
 */
public class ImportRejection {

    public int rowNumber;
    public String reason;

    /**
     * De quoi reconnaître la ligne dans le fichier : une référence, un
     * nom, un numéro de bordereau. Jamais la ligne entière, qui gonflerait
     * le journal sans rien apprendre de plus.
     */
    public String rowHint;

    public ImportRejection() {}

    public ImportRejection(int rowNumber, String reason, String rowHint) {
        this.rowNumber = rowNumber;
        this.reason = reason;
        this.rowHint = rowHint;
    }
}
