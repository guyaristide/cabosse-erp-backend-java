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

    /**
     * Section de collecte du délégué. {@code null} si non délégué.
     *
     * <p><strong>Dérivée</strong> de {@link #localityIds} dès que celles-ci
     * sont renseignées : c'est la localité qui porte le rattachement, la
     * section n'en étant que le regroupement. Le champ reste écrit pour les
     * lectures qui l'utilisent déjà (compte courant, avances, états) et pour
     * les structures qui n'ont pas encore rangé leurs localités.</p>
     */
    public UUID sectionId;

    /**
     * Localités où le délégué collecte. Vide si non délégué.
     *
     * <p>Règle de l'expert : une localité est gérée par <strong>un seul</strong>
     * délégué, un délégué intervient dans plusieurs localités. Le rattachement
     * porte donc ici, au niveau atomique. Rattaché à une section, on ne savait
     * pas qui collecte dans un village donné.</p>
     */
    public java.util.List<UUID> localityIds = new java.util.ArrayList<>();

    /**
     * Rémunération du délégué sur les reçus qui lui sont rattachés, dans
     * l'unité du mode retenu au niveau du tenant (montant par kilo ou
     * pourcentage). {@code null} : le taux du tenant s'applique. Un délégué
     * mieux payé qu'un autre n'est pas une exception, c'est la règle sur le
     * terrain.
     */
    public java.math.BigDecimal collectorMarginRate;

    /**
     * Rémunération de ce délégué, campagne par campagne.
     *
     * <p>Une campagne se négocie : le taux d'une saison n'engage pas la
     * suivante. L'entrée d'une campagne l'emporte donc sur le taux commun
     * du délégué, qui reste le repli quand rien n'a été convenu pour la
     * campagne en cours.</p>
     *
     * <p>Seul le taux varie par campagne, pas le mode : c'est le tenant ou
     * la catégorie qui décide si l'on rémunère au kilo ou au pourcentage,
     * et changer d'unité en cours d'exercice rendrait deux campagnes
     * incomparables.</p>
     */
    public java.util.List<CampaignMargin> collectorMarginByCampaign = new java.util.ArrayList<>();

    /** Taux convenu pour une campagne donnée. */
    public static class CampaignMargin {
        public java.util.UUID campaignId;
        public java.math.BigDecimal rate;

        public CampaignMargin() {}

        public CampaignMargin(java.util.UUID campaignId, java.math.BigDecimal rate) {
            this.campaignId = campaignId;
            this.rate = rate;
        }
    }

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
