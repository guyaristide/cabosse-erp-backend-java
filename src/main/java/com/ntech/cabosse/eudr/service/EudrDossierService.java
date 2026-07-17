package com.ntech.cabosse.eudr.service;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.eudr.entity.EudrDocument;
import com.ntech.cabosse.eudr.entity.EudrDocumentType;
import com.ntech.cabosse.eudr.entity.EudrDossierEntity;
import com.ntech.cabosse.eudr.entity.EudrRiskLevel;
import com.ntech.cabosse.eudr.entity.EudrStatus;
import com.ntech.cabosse.eudr.repository.EudrDossierRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gestion du cycle de vie des dossiers EUDR. Auto-création à la création
 * de parcelle (via hook {@code ParcelService}) si la capacité
 * {@code HAS_EUDR_COMPLIANCE} est active sur le tenant.
 */
@ApplicationScoped
public class EudrDossierService {

    /** Durée de validité d'un statut COMPLIANT après revue. */
    private static final int COMPLIANCE_VALID_MONTHS = 12;

    @Inject EudrDossierRepository dossiers;
    @Inject ParcelRepository parcels;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Auto-création à la création de parcelle ────────────────────

    /**
     * Crée un dossier EUDR vierge pour la parcelle si elle n'en a pas
     * déjà un. Appelé par {@code ParcelService.create()} quand la
     * capacité {@code HAS_EUDR_COMPLIANCE} est active.
     */
    public EudrDossierEntity bootstrapForParcel(ParcelEntity parcel) {
        if (parcel == null) throw new BusinessException("Parcelle requise.");
        return dossiers.findByParcel(parcel.id).orElseGet(() -> {
            EudrDossierEntity d = new EudrDossierEntity();
            d.id = idGenerator.newId();
            d.parcelId = parcel.id;
            d.parcelCode = parcel.code;
            d.parcelName = parcel.name;
            d.status = EudrStatus.NOT_STARTED;
            d.riskLevel = EudrRiskLevel.UNKNOWN;
            d.createdAt = Instant.now();
            d.updatedAt = d.createdAt;
            d.createdBy = safeUserId();
            dossiers.insert(d);
            return d;
        });
    }

    // ─── Lectures ───────────────────────────────────────────────────

    public long count(EudrStatus statusFilter, String q) {
        return dossiers.countSearch(statusFilter, q);
    }

    public List<EudrDossierEntity> list(EudrStatus statusFilter, String q, int skip, int limit) {
        return dossiers.search(statusFilter, q, skip, limit);
    }

    public EudrDossierEntity getByParcel(UUID parcelId) {
        return dossiers.findByParcel(parcelId)
                .orElseThrow(() -> new NotFoundException("Dossier EUDR introuvable pour parcelle " + parcelId));
    }

    // ─── Documents ──────────────────────────────────────────────────

    public EudrDossierEntity addDocument(UUID parcelId, EudrDocument doc) {
        EudrDossierEntity d = getByParcel(parcelId);
        if (doc.id == null) doc.id = idGenerator.newId();
        if (doc.uploadedAt == null) doc.uploadedAt = Instant.now();
        if (doc.uploadedByEmail == null) doc.uploadedByEmail = actor();
        d.documents.add(doc);
        // Tout ajout de pièce passe le dossier en IN_PROGRESS si NOT_STARTED.
        if (d.status == EudrStatus.NOT_STARTED) d.status = EudrStatus.IN_PROGRESS;
        d.updatedAt = Instant.now();
        dossiers.replace(d);
        return d;
    }

    public EudrDossierEntity removeDocument(UUID parcelId, UUID docId) {
        EudrDossierEntity d = getByParcel(parcelId);
        d.documents.removeIf(doc -> docId.equals(doc.id));
        d.updatedAt = Instant.now();
        dossiers.replace(d);
        return d;
    }

    // ─── Revue & changement de statut ──────────────────────────────

    public EudrDossierEntity markCompliant(UUID parcelId, EudrRiskLevel risk, String notes) {
        EudrDossierEntity d = getByParcel(parcelId);
        ensureMinimalDocuments(d);
        d.status = EudrStatus.COMPLIANT;
        d.riskLevel = risk != null ? risk : EudrRiskLevel.STANDARD;
        d.complianceExpiresOn = LocalDate.now().plusMonths(COMPLIANCE_VALID_MONTHS);
        d.exclusionReason = null;
        d.lastReviewedAt = Instant.now();
        d.lastReviewedByEmail = actor();
        if (notes != null && !notes.isBlank()) d.notes = notes.trim();
        d.updatedAt = Instant.now();
        dossiers.replace(d);
        return d;
    }

    public EudrDossierEntity markNonCompliant(UUID parcelId, String exclusionReason) {
        if (exclusionReason == null || exclusionReason.isBlank()) {
            throw new BusinessException("Motif d'exclusion requis pour passer en NON_COMPLIANT.");
        }
        EudrDossierEntity d = getByParcel(parcelId);
        d.status = EudrStatus.NON_COMPLIANT;
        d.exclusionReason = exclusionReason.trim();
        d.complianceExpiresOn = null;
        d.lastReviewedAt = Instant.now();
        d.lastReviewedByEmail = actor();
        d.updatedAt = Instant.now();
        dossiers.replace(d);
        return d;
    }

    /**
     * Règle minimale au MVP : pour passer en COMPLIANT il faut au moins
     * 1 pièce de type LAND_TITLE ou MAYOR_ATTESTATION + 1 pièce
     * SATELLITE_ANALYSIS ou GPS_REPORT.
     */
    private void ensureMinimalDocuments(EudrDossierEntity d) {
        boolean hasLegal = d.documents.stream().anyMatch(doc ->
                doc.type == EudrDocumentType.LAND_TITLE || doc.type == EudrDocumentType.MAYOR_ATTESTATION);
        boolean hasGeo = d.documents.stream().anyMatch(doc ->
                doc.type == EudrDocumentType.SATELLITE_ANALYSIS || doc.type == EudrDocumentType.GPS_REPORT);
        if (!hasLegal) {
            throw new BusinessException(
                    "Pièce légale manquante (titre foncier ou attestation mairie) avant de passer en COMPLIANT.");
        }
        if (!hasGeo) {
            throw new BusinessException(
                    "Pièce géographique manquante (analyse satellite ou rapport GPS) avant de passer en COMPLIANT.");
        }
    }
}
