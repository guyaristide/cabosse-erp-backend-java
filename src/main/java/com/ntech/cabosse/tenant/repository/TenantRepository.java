package com.ntech.cabosse.tenant.repository;

import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository {@link TenantEntity} (plan contrôle). Utilise Panache parce
 * que la base cible est fixe ({@code cabosse_control}) — l'active record
 * statique résout correctement au compile-time (cf.
 * multi-tenant-architecture.md §8.1).
 *
 * <p>Aucune logique métier ici : transitions de statut, validation
 * d'unicité, audit → dans les services.</p>
 */
@ApplicationScoped
public class TenantRepository implements PanacheMongoRepositoryBase<TenantEntity, UUID> {

    public Optional<TenantEntity> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public boolean slugExists(String slug) {
        return count("slug", slug) > 0;
    }

    public List<TenantEntity> findByStatus(TenantStatus status, int skip, int limit) {
        return find("status", Sort.descending("createdAt"), status)
                .page(skip / Math.max(limit, 1), limit)
                .list();
    }

    public long countByStatus(TenantStatus status) {
        return count("status", status);
    }
}
