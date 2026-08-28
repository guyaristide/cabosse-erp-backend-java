package com.ntech.cabosse.supplier.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/** Fournisseur (B2B). Tenant-scoped. */
public class SupplierEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    /** Raison sociale (si différente du nom commercial). */
    public String legalName;

    /** Identifiant fiscal (RCCM, NIF…). */
    public String taxNumber;

    public String email;
    public String phone;
    public String addressLine;
    public String cityName;
    public String countryCode;

    /** Contact principal. */
    public String contactName;

    /** Conditions de règlement libre ({@code 30j fin de mois}). */
    public String paymentTerms;

    public String notes;

    /**
     * Fournisseur qui est un délégué collecteur (backlog ACH-02) :
     * représentant d'une section auprès duquel la coopérative fait des
     * avances pour sourcer la matière première.
     */
    public boolean collector = false;

    /** Section de collecte rattachée au délégué. {@code null} si non délégué. */
    public UUID sectionId;

    /**
     * Rémunération du délégué sur les reçus qui lui sont rattachés, dans
     * l'unité du mode retenu au niveau du tenant (montant par kilo ou
     * pourcentage). {@code null} : le taux du tenant s'applique. Un délégué
     * mieux payé qu'un autre n'est pas une exception, c'est la règle sur le
     * terrain.
     */
    public java.math.BigDecimal collectorMarginRate;

    /**
     * Mise en compte : retenue en FCFA par kilo convenue sur chaque
     * livraison du délégué.
     *
     * <p>À ne pas confondre avec la marge de fonctionnement, qui est ce
     * qu'on lui verse. La mise en compte est ce qu'on lui retient, et elle
     * vient en apurement de sa dette au même titre que la marchandise
     * livrée. Elle se négocie délégué par délégué, usuellement entre 10 et
     * 35 FCFA/kg.</p>
     *
     * <p>Elle devient obligatoire dès que le délégué porte une dette
     * antérieure non apurée : c'est la contrepartie qu'exige la
     * coopérative pour le refinancer malgré son solde.</p>
     */
    public java.math.BigDecimal collectorRetentionPerKgFcfa;

    /**
     * Catégorie de reprise du fournisseur (backlog ACH-07). {@code null} :
     * le fournisseur n'est rattaché à aucune catégorie et suit le réglage
     * du tenant.
     */
    public UUID categoryId;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
