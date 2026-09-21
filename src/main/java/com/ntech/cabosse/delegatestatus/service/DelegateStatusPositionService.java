package com.ntech.cabosse.delegatestatus.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.collector.service.DelegateAccountService;
import com.ntech.cabosse.delegatestatus.dto.DelegateStatusPositionCreateDto;
import com.ntech.cabosse.delegatestatus.dto.DelegateStatusPositionDto;
import com.ntech.cabosse.delegatestatus.entity.DelegateStatusEntity;
import com.ntech.cabosse.delegatestatus.entity.DelegateStatusPositionEntity;
import com.ntech.cabosse.delegatestatus.repository.DelegateStatusPositionRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Prise et lecture des positions tenues sur un délégué (DEL-01, DEL-03).
 *
 * <p>Écriture en ajout seul. Corriger une position se fait en en posant une
 * nouvelle : l'historique garde alors la trace de la correction, ce qu'un
 * champ modifiable aurait effacé.</p>
 */
@ApplicationScoped
public class DelegateStatusPositionService {

    @Inject DelegateStatusPositionRepository positions;
    @Inject DelegateStatusService statuses;
    @Inject SupplierRepository suppliers;
    @Inject CampaignRepository campaigns;
    @Inject DelegateAccountService accounts;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    /**
     * Pose une position sur un délégué, en figeant ce qu'il doit ce jour-là.
     *
     * <p>Le montant est lu par le serveur, toutes campagnes confondues :
     * une dette laissée par la campagne précédente compte autant que celle
     * de la campagne en cours, et c'est bien le total qui est en jeu quand
     * on classe un délégué douteux.</p>
     */
    public DelegateStatusPositionDto record(UUID delegateSupplierId,
                                            DelegateStatusPositionCreateDto payload) {
        SupplierEntity delegate = loadDelegateOrFail(delegateSupplierId);
        DelegateStatusEntity status = statuses.loadOrFail(payload.statusId());

        DelegateStatusPositionEntity e = new DelegateStatusPositionEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.delegateSupplierId = delegate.id;
        e.statusId = status.id;
        // Recopiés : l'historique reste lisible si le libellé du référentiel change.
        e.statusCode = status.code;
        e.statusLabel = status.label;
        e.effectiveDate = payload.effectiveDate() == null ? LocalDate.now() : payload.effectiveDate();
        e.reason = payload.reason().trim();
        e.owedAmount = owedNow(delegate.id);

        Optional<CampaignEntity> current = campaigns.findCurrent();
        e.campaignId = current.map(c -> c.id).orElse(null);
        e.campaignLabel = current.map(c -> c.label).orElse(null);

        e.createdAt = Instant.now();
        e.createdBy = tenantContext.userId();
        e.createdByEmail = actor();
        positions.insert(e);

        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("delegate_status_position", delegate.id.toString(), delegate.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Position « " + status.label + " » sur le délégué " + delegate.name
                        + ", dû figé à " + e.owedAmount + " : " + e.reason)
                .record();

        return DelegateStatusPositionDto.from(e);
    }

    /** Historique complet d'un délégué, du plus récent au plus ancien. */
    public List<DelegateStatusPositionDto> history(UUID delegateSupplierId) {
        loadDelegateOrFail(delegateSupplierId);
        return positions.history(delegateSupplierId).stream()
                .map(DelegateStatusPositionDto::from)
                .toList();
    }

    /** Position courante de chaque délégué, pour l'état des avances. */
    public Map<UUID, DelegateStatusPositionEntity> currentByDelegate() {
        return positions.currentByDelegate();
    }

    /** Ce que le délégué doit aujourd'hui, toutes campagnes confondues. */
    public BigDecimal owedNow(UUID delegateSupplierId) {
        return accounts.outstanding(delegateSupplierId, null);
    }

    private SupplierEntity loadDelegateOrFail(UUID id) {
        SupplierEntity s = suppliers.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.sup-not-found", id)));
        if (!s.collector) {
            throw new NotFoundException(Messages.msg("m.dst-not-a-delegate", s.name));
        }
        return s;
    }

    private String actor() {
        return jwt == null ? null : jwt.getName();
    }
}
