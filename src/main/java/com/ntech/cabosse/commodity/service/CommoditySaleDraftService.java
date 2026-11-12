package com.ntech.cabosse.commodity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.commodity.dto.CommoditySaleDraftResponseDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleResponseDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleUpsertDto;
import com.ntech.cabosse.commodity.entity.CommoditySaleDraftEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleDraftRepository;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.dispatch.repository.DispatchNoteRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.site.repository.SiteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Les brouillons de vente en gros (VTE-28, demandé le 12/11/2026).
 *
 * <p>« Enregistrer un brouillon pour permettre à la personne de venir
 * valider calmement après, en cas d'urgence sur d'autres dossiers. » Le
 * brouillon garde la saisie, même incomplète, sans rien écrire ni rien
 * sortir ; la validation est la création ordinaire d'une vente, avec
 * tous ses contrôles, et le brouillon disparaît une fois la vente née.
 * Un bordereau appelé par un brouillon reste libre : c'est la validation
 * qui tranche, comme pour toute vente.</p>
 */
@ApplicationScoped
public class CommoditySaleDraftService {

    @Inject CommoditySaleDraftRepository repo;
    @Inject CommoditySaleService sales;
    @Inject CustomerRepository customers;
    @Inject ArticleRepository articles;
    @Inject SiteRepository sites;
    @Inject DispatchNoteRepository dispatchNotes;
    @Inject ObjectMapper json;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    public List<CommoditySaleDraftResponseDto> list() {
        return repo.listAll().stream().map(e -> CommoditySaleDraftResponseDto.from(e, null)).toList();
    }

    public CommoditySaleDraftResponseDto get(UUID id) {
        CommoditySaleDraftEntity e = loadOrFail(id);
        return CommoditySaleDraftResponseDto.from(e, read(e));
    }

    /** Un brouillon neuf, ou le même repris : la saisie remplace la précédente. */
    public CommoditySaleDraftResponseDto save(UUID id, CommoditySaleUpsertDto payload) {
        Instant now = Instant.now();
        CommoditySaleDraftEntity e;
        if (id == null) {
            e = new CommoditySaleDraftEntity();
            e.id = idGenerator.newId();
            e.createdAt = now;
            e.createdBy = safeUserId();
            e.createdByEmail = actor();
        } else {
            e = loadOrFail(id);
        }
        e.payloadJson = write(payload);
        summarize(e, payload);
        e.updatedAt = now;
        e.updatedByEmail = actor();
        if (id == null) repo.insert(e); else repo.replace(e);
        return CommoditySaleDraftResponseDto.from(e, payload);
    }

    /**
     * La vente naît de la saisie présentée, par le chemin ordinaire et
     * avec tous ses contrôles ; le brouillon n'est effacé qu'ensuite. Si
     * la vente est refusée, le brouillon reste tel quel.
     */
    public CommoditySaleResponseDto validate(UUID id, CommoditySaleUpsertDto payload) {
        loadOrFail(id);
        CommoditySaleResponseDto sale = sales.create(payload);
        repo.delete(id);
        return sale;
    }

    public void delete(UUID id) {
        if (!repo.delete(id)) {
            throw new NotFoundException(Messages.msg("m.cco-draft-not-found", id));
        }
    }

    // ─── Internals ───

    private CommoditySaleDraftEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cco-draft-not-found", id)));
    }

    /** Ce que la liste montre sans rouvrir la saisie ; les noms sont relus aux référentiels. */
    private void summarize(CommoditySaleDraftEntity e, CommoditySaleUpsertDto p) {
        e.date = p.date();
        e.customerId = p.customerId();
        e.customerName = p.customerId() == null ? null
                : customers.findById(p.customerId()).map(c -> c.name).orElse(null);
        e.articleId = p.articleId();
        e.articleName = p.articleId() == null ? null
                : articles.findById(p.articleId()).map(a -> a.name).orElse(null);
        e.siteId = p.siteId();
        e.siteName = p.siteId() == null ? null
                : sites.findById(p.siteId()).map(s -> s.name).orElse(null);
        e.acceptedKg = p.weights() == null ? null : p.weights().acceptedKg();

        LinkedHashSet<UUID> noteIds = new LinkedHashSet<>();
        if (p.dispatchNoteIds() != null) p.dispatchNoteIds().stream().filter(java.util.Objects::nonNull).forEach(noteIds::add);
        if (p.dispatchNoteId() != null) noteIds.add(p.dispatchNoteId());
        List<String> refs = new ArrayList<>();
        BigDecimal fromNotes = BigDecimal.ZERO;
        boolean anyNote = false;
        for (UUID noteId : noteIds) {
            var note = dispatchNotes.findById(noteId).orElse(null);
            if (note == null) continue;
            anyNote = true;
            refs.add(note.ref);
            if (note.totalNetKg != null) fromNotes = fromNotes.add(note.totalNetKg);
        }
        e.dispatchNoteRefs = refs;
        e.declaredKg = anyNote ? fromNotes : (p.weights() == null ? null : p.weights().declaredKg());
    }

    private String write(CommoditySaleUpsertDto payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Brouillon de vente illisible", ex);
        }
    }

    private CommoditySaleUpsertDto read(CommoditySaleDraftEntity e) {
        try {
            return json.readValue(e.payloadJson, CommoditySaleUpsertDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Brouillon de vente illisible : " + e.id, ex);
        }
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
