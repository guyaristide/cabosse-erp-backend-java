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

    /**
     * Révocation atomique d'un token par hash, seulement s'il n'est pas
     * déjà révoqué. Mono-document et conditionnelle : deux appels
     * concurrents (double logout, logout pendant un refresh) se
     * sérialisent sur le document au lieu de provoquer un
     * {@code WriteConflict} transactionnel.
     *
     * @return {@code true} si ce call a effectivement révoqué le token.
     */
    public boolean revokeByHash(String tokenHash, Instant revokedAt, String reason) {
        UpdateResult result = mongoCollection().updateOne(
                Filters.and(
                        Filters.eq("tokenHash", tokenHash),
                        Filters.eq("revokedAt", null)
                ),
                Updates.combine(
                        Updates.set("revokedAt", revokedAt),
                        Updates.set("revokedReason", reason)
                )
        );
        return result.getModifiedCount() == 1;
    }

    /**
     * Marque un token comme rotaté, seulement s'il ne l'est pas déjà et
     * qu'il n'est pas révoqué. C'est le point de sérialisation des
     * rotations : sur N rotations concurrentes du même token, une seule
     * voit {@code true} — les autres doivent être traitées comme un rejeu.
     *
     * @return {@code true} si ce call a gagné la rotation.
     */
    public boolean markRotated(UUID id, Instant rotatedAt) {
        UpdateResult result = mongoCollection().updateOne(
                Filters.and(
                        Filters.eq("_id", id),
                        Filters.eq("rotatedAt", null),
                        Filters.eq("revokedAt", null)
                ),
                Updates.set("rotatedAt", rotatedAt)
        );
        return result.getModifiedCount() == 1;
    }
}
