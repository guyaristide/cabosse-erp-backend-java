package com.ntech.cabosse.agriculture.harvest.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Enregistrement d'une opération de récolte sur une parcelle.
 * Tenant-scopé (collection {@code harvests}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_PARCELS}
 * est active. Indépendant de la filière : utilisable par toute
 * production agricole (cacao, café, hévéa…).</p>
 *
 * <p>Une récolte saisie ne génère <strong>pas immédiatement</strong>
 * d'entrée stock — c'est l'aval (fermentation → séchage → QC) qui
 * pondère et qualifie les fèves avant entrée en magasin.</p>
 */
public class HarvestEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code HV-YYYY-NNNN}. Unique par tenant. */
    public String code;

    /** FK vers {@link com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity}. */
    public UUID parcelId;
    public String parcelCode;
    public String parcelName;

    /**
     * FK vers {@link com.ntech.cabosse.members.entity.MemberEntity}.
     * Optionnel : une parcelle peut être directement exploitée par le
     * tenant (sans membre adhérent).
     */
    public UUID memberId;
    public String memberName;

    /**
     * Année de la campagne agricole (ex. 2026). Une campagne dure
     * généralement d'octobre N à mars N+1, mais le système n'impose pas
     * de calendrier rigide — on saisit l'année de référence pour les
     * regroupements de paie membre.
     */
    public int campaignYear;

    public LocalDate harvestDate;

    /** Poids cabosses brutes (kg). */
    public BigDecimal cabossesKg;

    /** Poids fèves fraîches après écabossage (kg). */
    public BigDecimal freshBeansKg;

    /**
     * Notes qualité visuelle au champ ("récolte saine", "présence de
     * cabosses noires", "pluies récentes"…). Texte libre.
     */
    public String qualityNotes;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public HarvestEntity() {}
}
