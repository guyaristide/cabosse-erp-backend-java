package com.ntech.cabosse.user.repository;

import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository {@link UserEntity} (plan contrôle).
 *
 * <p>{@code findByEmail} est la voie d'entrée du flux d'authentification :
 * trouve le user par e-mail, puis vérifie le hash.</p>
 *
 * <p>{@code streamAll()} hérité de {@code PanacheMongoRepositoryBase} —
 * utilisé par {@code ConstantsConsistencyCheck} pour vérifier que tous
 * les rôles assignés sont connus de {@code Roles}.</p>
 */
@ApplicationScoped
public class UserRepository implements PanacheMongoRepositoryBase<UserEntity, UUID> {

    public Optional<UserEntity> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }

    public boolean emailExists(String email) {
        return count("email", email) > 0;
    }

    public List<UserEntity> findByTenant(UUID tenantId, int skip, int limit) {
        return find("tenantId", Sort.ascending("email"), tenantId)
                .page(skip / Math.max(limit, 1), limit)
                .list();
    }

    public long countByTenant(UUID tenantId) {
        return count("tenantId", tenantId);
    }

    public void disableAllForTenant(UUID tenantId) {
        update("status", UserStatus.DISABLED).where("tenantId", tenantId);
    }
}
