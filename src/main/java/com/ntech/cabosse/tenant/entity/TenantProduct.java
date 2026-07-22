package com.ntech.cabosse.tenant.entity;

import java.util.UUID;

/**
 * Produit vendu / collecté par la coopérative. Sub-document de
 * {@link TenantEntity}, embarqué dans la liste {@code productsSold}
 * (backlog COOP-03).
 *
 * <p>Liste légère (libellé + code court) découplée du référentiel Articles.
 * Sert de source au champ « produit livré » de la fiche membre. Le
 * {@code code} est stable (FK logique référencée par le membre), le
 * {@code label} est affiché.</p>
 *
 * <p>{@code articleId} : lien <strong>manuel</strong> vers un article matière
 * première (backlog NEG-01). L'achat de matière au producteur utilise le
 * produit ; le système résout cet article pour l'entrée stock + le CMUP.
 * Nullable tant que la coop ne l'a pas rattaché.</p>
 */
public class TenantProduct {

    /** Code court stable (ex. {@code "CACAO"}). */
    public String code;

    /** Libellé affiché (ex. {@code "Cacao"}). */
    public String label;

    /** Article matière première lié (référentiel Articles), pour le stock/CMUP. */
    public UUID articleId;

    public TenantProduct() {}

    public TenantProduct(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public TenantProduct(String code, String label, UUID articleId) {
        this.code = code;
        this.label = label;
        this.articleId = articleId;
    }
}
