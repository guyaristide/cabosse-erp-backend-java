package com.ntech.cabosse.accounting.entity;

/**
 * Le journal auquel une écriture appartient, au sens SYSCOHADA.
 *
 * <p>Ajouté le 13/09/2026 à la demande de l'expert-comptable. Le produit
 * n'avait qu'un journal, et le fichier des écritures comptables sortait
 * « GEN » pour tout : un cabinet qui reçoit ce fichier ne peut pas
 * rapprocher ses journaux, et un financier ne peut pas lire ce qui
 * concerne son module.</p>
 *
 * <p>Le code se déduit de l'écriture, il ne se stocke pas : la même
 * information existe déjà dans la nature de l'opération et dans les
 * comptes mouvementés. La stocker ouvrirait la porte à deux vérités.</p>
 */
public enum JournalCode {

    ACH("Journal des achats"),
    VTE("Journal des ventes"),
    BQ("Journal de banque"),
    CA("Journal de caisse"),
    OD("Opérations diverses");

    private final String label;

    JournalCode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
