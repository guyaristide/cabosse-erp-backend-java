package com.ntech.cabosse.eudr.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dossier de conformité EUDR pour une parcelle agricole. Tenant-scopé
 * (collection {@code eudr_dossiers}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_EUDR_COMPLIANCE}
 * est active. <strong>Auto-créé en statut {@link EudrStatus#NOT_STARTED}
 * à chaque création de parcelle</strong> par {@code ParcelService} si la
 * capacité est active.</p>
 *
 * <p>Identité métier : couple {@code (parcelId)} — index unique en BD.
 * Une parcelle a au plus un dossier EUDR.</p>
 *
 * <p>Le dossier porte la liste des pièces justificatives ({@code documents})
 * et un statut macro qui pilote l'éligibilité de la parcelle aux exports
 * UE. Quand un lot est issu d'une parcelle en {@link EudrStatus#NON_COMPLIANT}
 * ou {@link EudrStatus#EXPIRED}, la DDR ne peut pas être générée.</p>
 */
public class EudrDossierEntity {

    @BsonId
    public UUID id;

    /** FK vers {@link com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity#id}. */
    public UUID parcelId;

    /** Snapshot du code parcelle pour les listes (PR-YYYY-NNNN). */
    public String parcelCode;
    /** Snapshot du nom parcelle. */
    public String parcelName;

    public EudrStatus status = EudrStatus.NOT_STARTED;
    public EudrRiskLevel riskLevel = EudrRiskLevel.UNKNOWN;

    public List<EudrDocument> documents = new ArrayList<>();

    /** Date de la dernière revue manuelle du dossier. */
    public Instant lastReviewedAt;
    public String lastReviewedByEmail;

    /** Date d'expiration du statut COMPLIANT (typiquement +12 mois après revue). */
    public LocalDate complianceExpiresOn;

    /** Justification obligatoire si {@link EudrStatus#NON_COMPLIANT}. */
    public String exclusionReason;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public EudrDossierEntity() {}
}
