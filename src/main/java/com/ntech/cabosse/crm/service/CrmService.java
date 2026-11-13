package com.ntech.cabosse.crm.service;

import com.ntech.cabosse.crm.dto.InteractionCreateDto;
import com.ntech.cabosse.crm.dto.InteractionResponseDto;
import com.ntech.cabosse.crm.dto.PipelineSummaryDto;
import com.ntech.cabosse.crm.dto.ProspectContactDto;
import com.ntech.cabosse.crm.dto.ProspectResponseDto;
import com.ntech.cabosse.crm.dto.ProspectStageDto;
import com.ntech.cabosse.crm.dto.ProspectUpsertDto;
import com.ntech.cabosse.crm.dto.TenantFollowUpDto;
import com.ntech.cabosse.crm.entity.InteractionEntity;
import com.ntech.cabosse.crm.entity.ProspectEntity;
import com.ntech.cabosse.crm.entity.ProspectStage;
import com.ntech.cabosse.crm.entity.TenantFollowUpEntity;
import com.ntech.cabosse.crm.repository.InteractionRepository;
import com.ntech.cabosse.crm.repository.ProspectRepository;
import com.ntech.cabosse.crm.repository.TenantFollowUpRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le petit CRM de l'éditeur (SAAS-31, demandé le 13/11/2026).
 *
 * <p>Trois objets : le prospect, l'échange, la prochaine action. Le
 * prospect avance d'étape en étape sans contrainte ; chaque échange se
 * consigne avec sa date et son canal, et peut poser la prochaine action
 * dans le même geste. Gagné, le prospect se relie à la structure créée
 * et son fil d'échanges la suit.</p>
 */
@ApplicationScoped
public class CrmService {

    @Inject ProspectRepository prospects;
    @Inject InteractionRepository interactions;
    @Inject TenantFollowUpRepository followUps;
    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject IdGenerator idGenerator;

    /** Qui fait le geste, pour signer ce qu'il consigne. */
    public record Actor(String email, UUID userId) {}

    // ─── Prospects ───

    public Pagination<ProspectResponseDto> search(ProspectStage stage, String q, PageRequest pr) {
        List<ProspectResponseDto> items = prospects.search(stage, q, pr.page(), pr.perPage())
                .stream().map(this::toDto).toList();
        Map<String, String> filters = new HashMap<>();
        if (stage != null) filters.put("stage", stage.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(prospects.countSearch(stage, q), pr,
                new String[]{"updatedAt"}, "desc", filters, items);
    }

    public PipelineSummaryDto summary() {
        Map<ProspectStage, Long> byStage = new EnumMap<>(ProspectStage.class);
        for (ProspectStage s : ProspectStage.values()) byStage.put(s, 0L);
        byStage.putAll(prospects.countByStage());
        return new PipelineSummaryDto(byStage, prospects.countDue(LocalDate.now()));
    }

    public ProspectResponseDto get(UUID id) {
        return toDto(loadOrFail(id));
    }

    public ProspectResponseDto create(ProspectUpsertDto dto, Actor actor) {
        Instant now = Instant.now();
        ProspectEntity p = new ProspectEntity();
        p.id = idGenerator.newId();
        p.stage = ProspectStage.CONTACTED;
        p.stageChangedAt = now;
        p.createdAt = now;
        p.createdByEmail = actor.email();
        apply(p, dto);
        if (p.ownerEmail == null) p.ownerEmail = actor.email();
        p.updatedAt = now;
        p.updatedByEmail = actor.email();
        prospects.persist(p);
        return toDto(p);
    }

    public ProspectResponseDto update(UUID id, ProspectUpsertDto dto, Actor actor) {
        ProspectEntity p = loadOrFail(id);
        apply(p, dto);
        touch(p, actor);
        prospects.update(p);
        return toDto(p);
    }

    /** L'étape change librement ; perdu garde son motif, revenu l'efface. */
    public ProspectResponseDto changeStage(UUID id, ProspectStageDto dto, Actor actor) {
        ProspectEntity p = loadOrFail(id);
        if (p.stage != dto.stage()) {
            p.stage = dto.stage();
            p.stageChangedAt = Instant.now();
        }
        p.lostReason = dto.stage() == ProspectStage.LOST ? clean(dto.lostReason()) : null;
        touch(p, actor);
        prospects.update(p);
        return toDto(p);
    }

    /**
     * La structure née du prospect. Le prospect passe gagné, et ses
     * échanges rejoignent la fiche de la structure.
     */
    public ProspectResponseDto linkTenant(UUID id, UUID tenantId, Actor actor) {
        ProspectEntity p = loadOrFail(id);
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        if (p.tenantId != null && !p.tenantId.equals(tenantId)) {
            throw new ConflictException(Messages.msg("m.crm-prospect-already-converted", p.name));
        }
        p.tenantId = tenantId;
        if (p.stage != ProspectStage.WON) {
            p.stage = ProspectStage.WON;
            p.stageChangedAt = Instant.now();
        }
        p.lostReason = null;
        touch(p, actor);
        prospects.update(p);
        interactions.attachProspectToTenant(p.id, tenantId);
        return toDto(p);
    }

    /** Un prospect devenu structure ne s'efface plus : son histoire est celle de la structure. */
    public void delete(UUID id) {
        ProspectEntity p = loadOrFail(id);
        if (p.tenantId != null) {
            throw new ConflictException(Messages.msg("m.crm-prospect-already-converted", p.name));
        }
        interactions.delete("prospectId", id);
        prospects.deleteById(id);
    }

    // ─── Échanges ───

    public List<InteractionResponseDto> listForProspect(UUID prospectId) {
        loadOrFail(prospectId);
        return interactions.listForProspect(prospectId).stream().map(InteractionResponseDto::from).toList();
    }

    public List<InteractionResponseDto> listForTenant(UUID tenantId) {
        tenantOrFail(tenantId);
        return interactions.listForTenant(tenantId).stream().map(InteractionResponseDto::from).toList();
    }

    public InteractionResponseDto addForProspect(UUID prospectId, InteractionCreateDto dto, Actor actor) {
        ProspectEntity p = loadOrFail(prospectId);
        InteractionEntity e = newInteraction(dto, actor);
        e.prospectId = p.id;
        e.tenantId = p.tenantId;
        interactions.persist(e);
        if (dto.nextActionAt() != null || clean(dto.nextActionLabel()) != null) {
            p.nextActionAt = dto.nextActionAt();
            p.nextActionLabel = clean(dto.nextActionLabel());
            touch(p, actor);
            prospects.update(p);
        }
        return InteractionResponseDto.from(e);
    }

    public InteractionResponseDto addForTenant(UUID tenantId, InteractionCreateDto dto, Actor actor) {
        tenantOrFail(tenantId);
        InteractionEntity e = newInteraction(dto, actor);
        e.tenantId = tenantId;
        interactions.persist(e);
        if (dto.nextActionAt() != null || clean(dto.nextActionLabel()) != null) {
            setFollowUp(tenantId, new TenantFollowUpDto(null, dto.nextActionAt(), dto.nextActionLabel(), null, null), actor);
        }
        return InteractionResponseDto.from(e);
    }

    // ─── Prochaine action sur une structure ───

    public TenantFollowUpDto followUp(UUID tenantId) {
        tenantOrFail(tenantId);
        return followUps.findByTenant(tenantId).map(TenantFollowUpDto::from).orElse(TenantFollowUpDto.empty());
    }

    public TenantFollowUpDto setFollowUp(UUID tenantId, TenantFollowUpDto dto, Actor actor) {
        tenantOrFail(tenantId);
        TenantFollowUpEntity e = followUps.findByTenant(tenantId).orElse(null);
        boolean fresh = e == null;
        if (fresh) {
            e = new TenantFollowUpEntity();
            e.id = idGenerator.newId();
            e.tenantId = tenantId;
        }
        if (dto.ownerEmail() != null) e.ownerEmail = clean(dto.ownerEmail());
        if (e.ownerEmail == null) e.ownerEmail = actor.email();
        e.nextActionAt = dto.nextActionAt();
        e.nextActionLabel = clean(dto.nextActionLabel());
        e.updatedAt = Instant.now();
        e.updatedByEmail = actor.email();
        if (fresh) followUps.persist(e); else followUps.update(e);
        return TenantFollowUpDto.from(e);
    }

    // ─── Internals ───

    private ProspectEntity loadOrFail(UUID id) {
        ProspectEntity p = prospects.findById(id);
        if (p == null) throw new NotFoundException(Messages.msg("m.crm-prospect-not-found", id));
        return p;
    }

    private TenantEntity tenantOrFail(UUID tenantId) {
        TenantEntity t = tenants.findById(tenantId);
        if (t == null) throw new NotFoundException(Messages.msg("m.tnt-not-found-2", tenantId));
        return t;
    }

    private static void apply(ProspectEntity p, ProspectUpsertDto dto) {
        p.name = dto.name().trim();
        p.organizationModel = dto.organizationModel();
        p.industry = clean(dto.industry());
        p.country = clean(dto.country());
        p.city = clean(dto.city());
        p.source = clean(dto.source());
        p.contacts = dto.contacts() == null ? List.of()
                : dto.contacts().stream().map(ProspectContactDto::toEntity).toList();
        p.ownerEmail = clean(dto.ownerEmail());
        p.nextActionAt = dto.nextActionAt();
        p.nextActionLabel = clean(dto.nextActionLabel());
        p.notes = clean(dto.notes());
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void touch(ProspectEntity p, Actor actor) {
        p.updatedAt = Instant.now();
        p.updatedByEmail = actor.email();
    }

    private InteractionEntity newInteraction(InteractionCreateDto dto, Actor actor) {
        InteractionEntity e = new InteractionEntity();
        e.id = idGenerator.newId();
        e.kind = dto.kind();
        e.date = dto.date();
        e.summary = dto.summary().trim();
        e.authorEmail = actor.email();
        e.authorName = displayName(actor);
        e.createdAt = Instant.now();
        return e;
    }

    /** Le jeton ne porte que l'adresse ; le nom se relit sur le compte. */
    private String displayName(Actor actor) {
        UserEntity u = actor.userId() == null ? null : users.findById(actor.userId());
        if (u == null) return actor.email();
        String name = ((u.firstName == null ? "" : u.firstName) + " " + (u.lastName == null ? "" : u.lastName)).trim();
        return name.isBlank() ? actor.email() : name;
    }

    private ProspectResponseDto toDto(ProspectEntity p) {
        String tenantName = null;
        if (p.tenantId != null) {
            TenantEntity t = tenants.findById(p.tenantId);
            tenantName = t == null ? null : t.name;
        }
        return ProspectResponseDto.from(p, tenantName);
    }
}
