package com.ntech.cabosse.commodity.service;

import com.ntech.cabosse.commodity.dto.SalesContractResponseDto;
import com.ntech.cabosse.commodity.dto.SalesContractUpsertDto;
import com.ntech.cabosse.commodity.entity.SalesContractEntity;
import com.ntech.cabosse.commodity.repository.SalesContractRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** CRUD des contrats de vente cacao (backlog NEG-02). */
@ApplicationScoped
public class SalesContractService {

    @Inject SalesContractRepository repo;
    @Inject CommodityRefService refService;
    @Inject CustomerRepository customers;
    @Inject CampaignService campaigns;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    public List<SalesContractResponseDto> list(UUID campaignId, UUID customerId) {
        return repo.list(campaignId, customerId).stream().map(SalesContractResponseDto::from).toList();
    }

    public SalesContractResponseDto getById(UUID id) {
        return SalesContractResponseDto.from(loadOrFail(id));
    }

    public SalesContractResponseDto create(SalesContractUpsertDto p) {
        SalesContractEntity e = new SalesContractEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextContract();
        e.customerId = p.customerId();
        e.customerName = customers.findById(p.customerId())
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cco-customer-not-found", p.customerId()))).name;
        CampaignEntity campaign = p.campaignId() != null ? campaigns.get(p.campaignId()) : campaigns.current();
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        apply(e, p);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        repo.insert(e);
        auditEvt(e, "Création");
        return SalesContractResponseDto.from(e);
    }

    public SalesContractResponseDto update(UUID id, SalesContractUpsertDto p) {
        SalesContractEntity e = loadOrFail(id);
        e.customerId = p.customerId();
        e.customerName = customers.findById(p.customerId())
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cco-customer-not-found", p.customerId()))).name;
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, "Modification");
        return SalesContractResponseDto.from(e);
    }

    public SalesContractResponseDto setActive(UUID id, boolean active) {
        SalesContractEntity e = loadOrFail(id);
        e.active = active;
        e.updatedAt = Instant.now();
        repo.replace(e);
        auditEvt(e, active ? "Réactivation" : "Désactivation");
        return SalesContractResponseDto.from(e);
    }

    private void apply(SalesContractEntity e, SalesContractUpsertDto p) {
        e.marginPerKg = nz(p.marginPerKg());
        e.label = (p.label() == null || p.label().isBlank()) ? null : p.label().trim();
        e.coopPrimePerKg = nz(p.coopPrimePerKg());
        e.producerPrimePerKg = nz(p.producerPrimePerKg());
        e.socialPrimePerKg = nz(p.socialPrimePerKg());
        e.notes = (p.notes() == null || p.notes().isBlank()) ? null : p.notes().trim();
    }

    private void auditEvt(SalesContractEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("sales_contract", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " contrat vente " + e.ref + " · " + e.customerName)
                .record();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private SalesContractEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cco-contract-not-found", id)));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
