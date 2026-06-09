package com.ntech.cabosse.eudr.service;

import com.ntech.cabosse.eudr.entity.DueDiligenceStatementEntity;
import com.ntech.cabosse.eudr.entity.DueDiligenceStatus;
import com.ntech.cabosse.eudr.entity.EudrDossierEntity;
import com.ntech.cabosse.eudr.entity.EudrStatus;
import com.ntech.cabosse.eudr.repository.DueDiligenceStatementRepository;
import com.ntech.cabosse.eudr.repository.EudrDossierRepository;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleLine;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Génération et cycle de vie des Déclarations de Diligence Raisonnée
 * (DDR) — EUDR Art. 4-5.
 *
 * <p>Pour chaque vente confirmée vers un client UE, le système peut
 * générer une DDR en brouillon qui agrège :
 * <ul>
 *   <li>les lots vendus (via {@code SaleLine.lotRef}),</li>
 *   <li>les parcelles d'origine éligibles (statut EUDR
 *       {@link EudrStatus#COMPLIANT}).</li>
 * </ul>
 *
 * <p><strong>Limite MVP</strong> : la remontée précise lot → parcelle
 * n'est pas tracée au cas par cas (cf.
 * {@code project-m9-tracabilite} pour les limites du modèle actuel —
 * inférence FIFO sans batch tracking strict). Au MVP, la DDR liste
 * <strong>toutes les parcelles COMPLIANT</strong> du tenant comme
 * origine possible. Le pin précis sera disponible Phase 7 quand le
 * batch tracking sera renforcé.</p>
 */
@ApplicationScoped
public class DueDiligenceService {

    @Inject DueDiligenceStatementRepository ddrs;
    @Inject DueDiligenceRefService refService;
    @Inject SaleRepository sales;
    @Inject EudrDossierRepository dossiers;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public List<DueDiligenceStatementEntity> list(DueDiligenceStatus statusFilter) {
        return ddrs.search(statusFilter);
    }

    /**
     * Génère ou récupère la DDR draft d'une vente. Idempotent : si une
     * DDR existe déjà pour la vente, retourne celle-ci sans dupliquer.
     */
    public DueDiligenceStatementEntity generateForSale(UUID saleId) {
        SaleEntity sale = sales.findById(saleId)
                .orElseThrow(() -> new NotFoundException("Vente " + saleId + " introuvable."));
        return ddrs.findBySale(saleId).orElseGet(() -> createNew(sale));
    }

    private DueDiligenceStatementEntity createNew(SaleEntity sale) {
        // Extraction lots vendus
        Set<String> lotRefs = new LinkedHashSet<>();
        if (sale.lines != null) {
            for (SaleLine line : sale.lines) {
                if (line.lotRef != null && !line.lotRef.isBlank()) {
                    lotRefs.add(line.lotRef);
                }
            }
        }
        // Parcelles éligibles (statut COMPLIANT) — limite MVP
        List<EudrDossierEntity> compliantDossiers = dossiers.search(EudrStatus.COMPLIANT, null);
        List<UUID> parcelIds = compliantDossiers.stream().map(d -> d.parcelId).toList();
        List<String> parcelCodes = compliantDossiers.stream().map(d -> d.parcelCode).toList();

        DueDiligenceStatementEntity ddr = new DueDiligenceStatementEntity();
        ddr.id = idGenerator.newId();
        ddr.ref = refService.next();
        ddr.saleId = sale.id;
        ddr.saleRef = sale.ref;
        ddr.customerName = sale.customerName;
        // Code pays destinataire : à priori celui du client, mais on n'a
        // pas le détail au MVP. On laisse vide pour saisie manuelle.
        ddr.exportCountryCode = null;
        ddr.lotRefs = new ArrayList<>(lotRefs);
        ddr.parcelIds = new ArrayList<>(parcelIds);
        ddr.parcelCodes = new ArrayList<>(parcelCodes);
        ddr.status = DueDiligenceStatus.DRAFT;
        ddr.generatedAt = LocalDate.now();
        ddr.createdAt = Instant.now();
        ddr.updatedAt = ddr.createdAt;
        ddr.createdBy = safeUserId();
        ddrs.insert(ddr);
        return ddr;
    }

    public DueDiligenceStatementEntity markReady(UUID ddrId, String exportCountryCode) {
        DueDiligenceStatementEntity ddr = load(ddrId);
        if (ddr.status != DueDiligenceStatus.DRAFT) {
            throw new BusinessException("DDR en statut " + ddr.status + " — non transitionnable vers READY.");
        }
        if (exportCountryCode == null || exportCountryCode.isBlank()) {
            throw new BusinessException("Code pays destinataire requis.");
        }
        ddr.exportCountryCode = exportCountryCode.trim().toUpperCase();
        ddr.status = DueDiligenceStatus.READY;
        ddr.updatedAt = Instant.now();
        ddrs.replace(ddr);
        return ddr;
    }

    public DueDiligenceStatementEntity markSubmitted(UUID ddrId, String eudrReferenceNumber) {
        DueDiligenceStatementEntity ddr = load(ddrId);
        if (ddr.status != DueDiligenceStatus.READY && ddr.status != DueDiligenceStatus.DRAFT) {
            throw new BusinessException("DDR en statut " + ddr.status + " — soumission impossible.");
        }
        if (eudrReferenceNumber == null || eudrReferenceNumber.isBlank()) {
            throw new BusinessException("N° référence EUDR (portail UE) requis.");
        }
        ddr.eudrReferenceNumber = eudrReferenceNumber.trim();
        ddr.status = DueDiligenceStatus.SUBMITTED;
        ddr.submittedAt = Instant.now();
        ddr.submittedByEmail = actor();
        ddr.updatedAt = Instant.now();
        ddrs.replace(ddr);
        return ddr;
    }

    public DueDiligenceStatementEntity markAccepted(UUID ddrId) {
        DueDiligenceStatementEntity ddr = load(ddrId);
        ddr.status = DueDiligenceStatus.ACCEPTED;
        ddr.acceptedAt = Instant.now();
        ddr.updatedAt = Instant.now();
        ddrs.replace(ddr);
        return ddr;
    }

    public DueDiligenceStatementEntity markRejected(UUID ddrId, String reason) {
        DueDiligenceStatementEntity ddr = load(ddrId);
        ddr.status = DueDiligenceStatus.REJECTED;
        ddr.rejectedAt = Instant.now();
        ddr.rejectionReason = reason != null ? reason.trim() : null;
        ddr.updatedAt = Instant.now();
        ddrs.replace(ddr);
        return ddr;
    }

    private DueDiligenceStatementEntity load(UUID id) {
        return ddrs.findById(id)
                .orElseThrow(() -> new NotFoundException("DDR " + id + " introuvable."));
    }
}
