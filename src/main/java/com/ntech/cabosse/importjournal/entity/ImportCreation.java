package com.ntech.cabosse.importjournal.entity;

import java.util.UUID;

/**
 * Une chose créée par un import, gardée pour pouvoir la défaire.
 *
 * <p>La nature accompagne l'identifiant : un import de producteurs crée
 * aussi des fournisseurs miroirs et des parcelles, et les défaire ne se
 * fait ni dans le même ordre ni avec les mêmes contrôles.</p>
 */
public class ImportCreation {

    /** {@code member}, {@code supplier}, {@code parcel}. */
    public String kind;

    public UUID id;

    /** Ce qui identifie la chose pour un œil humain, au compte rendu. */
    public String label;

    /** La ligne du fichier d'où elle vient, pour remonter à la source. */
    public int rowNumber;

    public ImportCreation() {}

    public ImportCreation(String kind, UUID id, String label, int rowNumber) {
        this.kind = kind;
        this.id = id;
        this.label = label;
        this.rowNumber = rowNumber;
    }
}
