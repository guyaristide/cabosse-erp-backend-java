package com.ntech.cabosse.auth.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.ntech.cabosse.auth.entity.RefreshTokenEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RefreshTokenRepository implements PanacheMongoRepositoryBase<RefreshTokenEntity, UUID> {

    public Optional<RefreshTokenEntity> findByHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResultOptional();
    }

    /**
     * Marque tous les tokens d'une famille (non encore révoqués) comme
     * révoqués. Utilisé sur détection de reuse pour invalider la chaîne
     * complète en une seule requête.
     *
     * <p>Implémentation via le driver Mongo direct : la syntaxe Panache
     * {@code update().where("... is null")} ne traduit pas correctement
     * le filtre {@code is null} dans cette version du driver, ce qui
     * laisse les rotations parallèles utilisables (vu en test).</p>
     */
    public long revokeFamily(UUID familyId, Instant revokedAt, String reason) {
        UpdateResult result = mongoCollection().updateMany(
                Filters.and(
                        Filters.eq("familyId", familyId),
                        Filters.eq("revokedAt", null)
                ),
                Updates.combine(
                        Updates.set("revokedAt", revokedAt),
                        Updates.set("revokedReason", reason)
                )
        );
        return result.getModifiedCount();
    }
}
