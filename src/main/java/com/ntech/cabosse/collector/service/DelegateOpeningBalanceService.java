package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceDto;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceUpsertDto;
import com.ntech.cabosse.collector.entity.DelegateOpeningBalanceEntity;
import com.ntech.cabosse.collector.repository.DelegateOpeningBalanceRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Le solde qu'un délégué traîne à l'ouverture d'une campagne.
 *
 * <p>Le report d'une campagne à la suivante se calcule déjà seul à partir
 * de ce que l'outil contient. Mais la première campagne portée par
 * l'outil n'a rien derrière elle : des délégués arrivaient débiteurs et
 * leur compte courant démarrait à zéro, ce qui fausse la mise en compte,
 * l'état des délégués et la décision de réavancer.</p>
 *
 * <p>C'est une reprise d'antériorité, au même titre que les écritures à
 * nouveau de la comptabilité ou l'amorçage du stock : on déclare ce que
 * l'outil n'a pas vu, une fois.</p>
 */
@ApplicationScoped
public class DelegateOpeningBalanceService {

    @Inject DelegateOpeningBalanceRepository repo;
    @Inject SupplierRepository suppliers;
    @Inject CampaignRepository campaigns;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject JsonWebToken jwt;

    public List<DelegateOpeningBalanceDto> list(UUID campaignId) {
        return repo.listByCampaign(campaignId).stream()
                .map(DelegateOpeningBalanceDto::from).toList();
    }

    /** Le montant déclaré, ou zéro : l'absence de saisie n'est pas une erreur. */
    public BigDecimal amountFor(UUID delegateSupplierId, UUID campaignId) {
        if (delegateSupplierId == null || campaignId == null) return BigDecimal.ZERO;
        return repo.find(delegateSupplierId, campaignId)
                .map(e -> e.amount == null ? BigDecimal.ZERO : e.amount)
                .orElse(BigDecimal.ZERO);
    }

    public DelegateOpeningBalanceDto upsert(UUID delegateSupplierId,
                                            DelegateOpeningBalanceUpsertDto payload) {
        SupplierEntity delegate = suppliers.findById(delegateSupplierId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.sup-not-found")));
        if (!delegate.collector) {
            throw new BusinessException(Messages.msg("m.dob-not-a-delegate", delegate.name));
        }
        var campaign = campaigns.findById(payload.campaignId())
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cmp-not-found")));

        DelegateOpeningBalanceEntity e = repo.find(delegateSupplierId, payload.campaignId())
                .orElseGet(DelegateOpeningBalanceEntity::new);
        boolean fresh = e.id == null;
        if (fresh) {
            e.id = idGenerator.newId();
            e.createdAt = Instant.now();
            e.createdByEmail = actor();
        }
        e.delegateSupplierId = delegate.id;
        e.delegateName = delegate.name;
        e.campaignId = campaign.id;
        e.campaignYear = campaign.campaignYear;
        e.amount = payload.amount();
        e.notes = payload.notes();
        e.updatedAt = Instant.now();
        e.updatedByEmail = actor();
        repo.upsert(e);

        // Une reprise d'antériorité change ce que le délégué doit sans
        // qu'aucune opération ne l'explique : elle se trace.
        audit.event(AuditEventType.DELEGATE_OPENING_BALANCE_SET)
                .actorEmail(actor())
                .target("delegate_opening_balance", delegate.id.toString(), delegate.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Solde d'ouverture de " + delegate.name + " sur "
                        + campaign.label + " : " + payload.amount())
                .record();

        return DelegateOpeningBalanceDto.from(e);
    }

    public void delete(UUID delegateSupplierId, UUID campaignId) {
        repo.delete(delegateSupplierId, campaignId);
        audit.event(AuditEventType.DELEGATE_OPENING_BALANCE_SET)
                .actorEmail(actor())
                .target("delegate_opening_balance", String.valueOf(delegateSupplierId), null)
                .tenant(tenantContext.tenantId(), null)
                .description("Solde d'ouverture supprimé")
                .record();
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
