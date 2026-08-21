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

    /**
     * Comptes consommant un siège du plan : actifs et invités. Un compte
     * désactivé libère sa place, sinon désactiver un ancien collaborateur
     * ne servirait à rien face au plafond.
     */
    public long countActiveByTenant(UUID tenantId) {
        return count("tenantId = ?1 and status != ?2", tenantId,
                com.ntech.cabosse.user.entity.UserStatus.DISABLED);
    }

    /**
     * Pose {@code lastLoginAt} en update atomique — jamais de
     * read-modify-replace sous transaction (deux logins parallèles du
     * même compte provoquaient un WriteConflict, cf. incident refresh).
     */
    public void touchLastLogin(UUID userId, java.time.Instant at) {
        mongoCollection().updateOne(
                com.mongodb.client.model.Filters.eq("_id", userId),
                com.mongodb.client.model.Updates.set("lastLoginAt", at));
    }

    /**
     * Consomme atomiquement un token d'invitation : le filtre exige que le
     * hash soit encore présent et le compte encore INVITED — le perdant
     * d'une double soumission voit {@code false} et reçoit un 401 propre.
     */
    public boolean consumeInvitation(String tokenHash, String newPasswordHash,
                                     java.time.Instant now) {
        return mongoCollection().updateOne(
                com.mongodb.client.model.Filters.and(
                        com.mongodb.client.model.Filters.eq("invitationTokenHash", tokenHash),
                        com.mongodb.client.model.Filters.eq("status", UserStatus.INVITED.name())
                ),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("passwordHash", newPasswordHash),
                        com.mongodb.client.model.Updates.set("status", UserStatus.ACTIVE.name()),
                        com.mongodb.client.model.Updates.unset("invitationTokenHash"),
                        com.mongodb.client.model.Updates.unset("invitationExpiresAt"),
                        com.mongodb.client.model.Updates.set("lastLoginAt", now),
                        com.mongodb.client.model.Updates.set("updatedAt", now)
                )
        ).getModifiedCount() == 1;
    }

    public void disableAllForTenant(UUID tenantId) {
        update("status", UserStatus.DISABLED).where("tenantId", tenantId);
    }
}
