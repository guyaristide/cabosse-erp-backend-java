package com.ntech.cabosse.me.service;

import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.me.dto.TenantActivityDto;
import com.ntech.cabosse.me.dto.UpdateTenantActivitiesDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantActivity;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gestion des activités économiques du tenant courant.
 *
 * <p>Les activités sont un <strong>embed</strong> dans {@code TenantEntity},
 * pas une collection séparée. Ce service expose un endpoint orienté
 * "remplacement total" : le tenant admin envoie la liste complète des
 * activités souhaitées, le service valide (codes catalogue + exactement
 * un primaire) et remplace l'embed.</p>
 *
 * <p>Les codes doivent référencer des entrées de la collection système
 * {@code industries} — la plateforme reste filière-agnostique mais ne
 * laisse pas un tenant inventer des codes arbitraires sans contrôle.</p>
 */
@ApplicationScoped
public class TenantActivitiesService {

    private static final org.jboss.logging.Logger LOG =
            org.jboss.logging.Logger.getLogger(TenantActivitiesService.class);

    @Inject TenantContext tenantContext;
    @Inject TenantRepository tenants;
    @Inject IndustryRepository industries;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.shared.migration.TenantMigrationRunner migrationRunner;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    public List<TenantActivityDto> list() {
        TenantEntity t = loadCurrentTenant();
        if (t.activities == null) return List.of();
        return t.activities.stream()
                .map(a -> new TenantActivityDto(a.code, a.label, a.isPrimary))
                .toList();
    }

    /**
     * Remplace la liste des activités. Tous les codes doivent exister
     * dans le catalogue {@code industries}, exactement une activité
     * doit être marquée primaire.
     */
    @Transactional
    public List<TenantActivityDto> replace(UpdateTenantActivitiesDto payload) {
        TenantEntity t = loadCurrentTenant();
        List<UpdateTenantActivitiesDto.Line> lines = payload.activities();

        // Validation : exactement un primaire.
        long primaries = lines.stream().filter(l -> Boolean.TRUE.equals(l.isPrimary())).count();
        if (primaries != 1) {
            throw new BusinessException(
                    "Exactement une activité doit être primaire (trouvé " + primaries + ")."
            );
        }

        // Validation : pas de doublons sur le code.
        Set<String> codes = new HashSet<>();
        for (UpdateTenantActivitiesDto.Line l : lines) {
            if (!codes.add(l.code())) {
                throw new BusinessException("Code « " + l.code() + " » référencé plusieurs fois.");
            }
        }

        // Validation : tous les codes existent dans le catalogue industries.
        Set<String> referenced = lines.stream()
                .map(UpdateTenantActivitiesDto.Line::code)
                .collect(Collectors.toSet());
        Set<String> missing = new HashSet<>(referenced);
        for (var code : referenced) {
            if (industries.findById(code) != null) missing.remove(code);
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    "Activités inconnues du catalogue : " + String.join(", ", missing)
            );
        }

        // Application : remplacement complet de l'embed.
        t.activities = new ArrayList<>();
        for (UpdateTenantActivitiesDto.Line l : lines) {
            t.activities.add(new TenantActivity(l.code(), l.label().trim(), Boolean.TRUE.equals(l.isPrimary())));
        }
        t.updatedAt = Instant.now();
        tenants.update(t);

        // Les activités dérivent les capacités, et les capacités
        // conditionnent des structures (collections, index, semis). Un
        // module activé maintenant doit fonctionner maintenant : on rejoue
        // les migrations du tenant, dont les conditionnelles sont
        // rejouables. En cas d'échec (verrou Mongock tenu par un autre
        // nœud, par exemple), on ne défait pas la mise à jour : le
        // démarrage suivant rattrape, c'est son rôle.
        try {
            migrationRunner.runMigrationsFor(t.databaseName);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Structures non rejouées après changement d'activités du tenant %s", t.id);
        }

        audit.event(AuditEventType.TENANT_UPDATED)
                .actorEmail(actor())
                .target("tenant", t.id.toString(), t.name)
                .tenant(t.id, t.name)
                .description("Liste des activités mise à jour (" + lines.size() + " activité"
                        + (lines.size() > 1 ? "s" : "") + ")")
                .record();

        return list();
    }

    private TenantEntity loadCurrentTenant() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        if (t == null) {
            throw new NotFoundException("Tenant courant introuvable.");
        }
        return t;
    }
}
