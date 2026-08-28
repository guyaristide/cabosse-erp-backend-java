package com.ntech.cabosse.collector.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.collector.dto.SectionResponseDto;
import com.ntech.cabosse.collector.dto.SectionCoverageDto;
import com.ntech.cabosse.collector.dto.SectionUpsertDto;
import com.ntech.cabosse.locality.entity.LocalityEntity;
import com.ntech.cabosse.locality.repository.LocalityRepository;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.collector.entity.SectionEntity;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Référentiel des sections de collecte du tenant (backlog ACH-02). */
@ApplicationScoped
public class SectionService {

    @Inject SectionRepository repo;
    @Inject LocalityRepository localityRepo;
    @Inject SupplierRepository supplierRepo;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }

    public List<SectionResponseDto> list() {
        return repo.listAll().stream().map(SectionResponseDto::from).toList();
    }

    public SectionResponseDto create(SectionUpsertDto p) {
        String code = (p.code() != null && !p.code().isBlank())
                ? p.code().trim().toUpperCase(Locale.ROOT) : slug(p.name());
        if (repo.codeExists(code)) {
            throw new ConflictException(Messages.msg("m.col-section-code-exists", code));
        }
        SectionEntity e = new SectionEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.code = code;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        apply(e, p);
        repo.insert(e);
        audit(e, "Création");
        return SectionResponseDto.from(e);
    }

    public SectionResponseDto update(UUID id, SectionUpsertDto p) {
        SectionEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-section-not-found", id)));
        apply(e, p);
        e.updatedAt = Instant.now();
        repo.replace(e);
        audit(e, "Modification");
        return SectionResponseDto.from(e);
    }

    public SectionResponseDto setActive(UUID id, boolean active) {
        SectionEntity e = repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-section-not-found", id)));
        if (e.active == active) return SectionResponseDto.from(e);
        repo.updateActive(id, active);
        e.active = active;
        e.updatedAt = Instant.now();
        audit(e, active ? "Réactivation" : "Désactivation");
        return SectionResponseDto.from(e);
    }

    /**
     * Couverture des sections par leurs délégués.
     *
     * <p>Rend vérifiable ce que le rattachement à la section n'exprimait
     * pas : quelles localités n'ont personne, et quelles sections reposent
     * sur un seul délégué.</p>
     */
    public SectionCoverageDto coverage() {
        // Une section ou un village désactivés ne sont plus exploités : les
        // signaler à découvert reprocherait à la structure d'avoir rangé
        // ce qu'elle n'utilise plus.
        var localities = localityRepo.listAll().stream().filter(l -> l.active).toList();
        var delegates = supplierRepo.listAll().stream()
                .filter(sup -> sup.collector && sup.active)
                .toList();

        // Quel délégué couvre quelle localité. Une localité n'en a qu'un :
        // la règle est tenue à l'écriture, on peut donc indexer sans risque.
        Map<UUID, SupplierEntity> holderByLocality = new HashMap<>();
        for (SupplierEntity sup : delegates) {
            if (sup.localityIds == null) continue;
            for (UUID lid : sup.localityIds) holderByLocality.putIfAbsent(lid, sup);
        }

        List<SectionCoverageDto.Section> out = new ArrayList<>();
        for (SectionEntity sec : repo.listAll()) {
            if (!sec.active) continue;
            var own = localities.stream().filter(l -> sec.id.equals(l.sectionId)).toList();
            List<SectionCoverageDto.Locality> uncovered = own.stream()
                    .filter(l -> !holderByLocality.containsKey(l.id))
                    .map(SectionService::localityOf)
                    .toList();

            Map<UUID, Integer> countByDelegate = new LinkedHashMap<>();
            for (var l : own) {
                SupplierEntity holder = holderByLocality.get(l.id);
                if (holder != null) countByDelegate.merge(holder.id, 1, Integer::sum);
            }
            List<SectionCoverageDto.Delegate> lines = new ArrayList<>();
            for (var entry : countByDelegate.entrySet()) {
                delegates.stream().filter(d -> d.id.equals(entry.getKey())).findFirst().ifPresent(sup ->
                        lines.add(new SectionCoverageDto.Delegate(
                                sup.id, sup.code, sup.name, entry.getValue())));
            }

            // Une section vide ou entièrement découverte n'est pas « tenue
            // par un seul » : elle n'est tenue par personne, ce que dit déjà
            // la liste des localités sans délégué.
            boolean single = !own.isEmpty() && uncovered.isEmpty() && lines.size() == 1;
            out.add(new SectionCoverageDto.Section(
                    sec.id, sec.code, sec.name, own.size(), uncovered, lines, single));
        }

        List<SectionCoverageDto.Locality> orphans = localities.stream()
                .filter(l -> l.sectionId == null)
                .map(SectionService::localityOf)
                .toList();
        return new SectionCoverageDto(out, orphans);
    }

    private static SectionCoverageDto.Locality localityOf(LocalityEntity l) {
        return new SectionCoverageDto.Locality(l.id, l.code, l.name);
    }

    private void apply(SectionEntity e, SectionUpsertDto p) {
        e.name = p.name().trim();
        e.description = (p.description() == null || p.description().isBlank()) ? null : p.description().trim();
    }

    private void audit(SectionEntity e, String action) {
        audit.event(AuditEventType.CATALOG_UPDATED)
                .actorEmail(actor())
                .target("section", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description(action + " section « " + e.code + " · " + e.name + " »")
                .record();
    }

    private static String slug(String name) {
        if (name == null) return "SEC";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "").trim();
        if (n.length() > 16) n = n.substring(0, 16);
        return n.isEmpty() ? "SEC" : n;
    }

    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
}
