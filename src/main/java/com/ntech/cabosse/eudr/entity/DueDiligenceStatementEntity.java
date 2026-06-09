package com.ntech.cabosse.eudr.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Déclaration de Diligence Raisonnée (DDR) — document réglementaire
 * exigé par l'EUDR Art. 4-5 pour chaque lot exporté vers l'UE.
 * Tenant-scopée (collection {@code due_diligence_statements}).
 *
 * <p>Une DDR est générée automatiquement à la confirmation d'une vente
 * dont le client est en zone UE. Elle référence les lots vendus
 * ({@link #lotRefs}) et, par remontée traçabilité, les parcelles dont
 * proviennent ces lots ({@link #parcelIds}).</p>
 *
 * <p>Au MVP : génération PDF et soumission manuelle via le portail UE
 * EUDR Information System. L'intégration API (Phase 7) automatisera la
 * soumission et permettra de récupérer {@link #eudrReferenceNumber}
 * automatiquement.</p>
 */
public class DueDiligenceStatementEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code DDR-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    /** FK vers {@link com.ntech.cabosse.sale.entity.SaleEntity#id}. */
    public UUID saleId;
    /** Snapshot {@code FA-YYYY-NNNN}. */
    public String saleRef;

    /** Référence client (snapshot pour affichage rapide). */
    public String customerName;

    /** Code pays destinataire ISO 3166-1 alpha-2 (FR, DE, IT, ES, NL…). */
    public String exportCountryCode;

    /** Références des lots vendus (LOT-YYYY-NNNN). */
    public List<String> lotRefs = new ArrayList<>();

    /** UUIDs des parcelles d'origine identifiées par traçabilité amont. */
    public List<UUID> parcelIds = new ArrayList<>();

    /** Snapshots des codes parcelles (PR-YYYY-NNNN) pour affichage. */
    public List<String> parcelCodes = new ArrayList<>();

    public DueDiligenceStatus status = DueDiligenceStatus.DRAFT;

    /** N° référence renvoyé par le portail UE après soumission. */
    public String eudrReferenceNumber;

    public LocalDate generatedAt;
    public Instant submittedAt;
    public String submittedByEmail;
    public Instant acceptedAt;
    public Instant rejectedAt;
    public String rejectionReason;

    /** Référence vers {@code CloudFileEntity.id} pour le PDF généré. */
    public UUID pdfFileId;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public DueDiligenceStatementEntity() {}
}
