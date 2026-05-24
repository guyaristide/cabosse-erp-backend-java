package com.ntech.cabosse.auth.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token rotaté. Vit dans le plan contrôle (cf. auth-architecture).
 *
 * <p>Le token visible côté client est une chaîne aléatoire opaque
 * (32 bytes base64url, ~43 caractères). En base on ne stocke que son
 * {@code SHA-256} hex. Aucun moyen de retrouver le token original depuis
 * la DB — si un dump fuit, l'attaquant ne peut pas réutiliser les tokens
 * mais peut tout au plus invalider des sessions (déni de service mineur).</p>
 *
 * <p>Chaque login démarre une <strong>famille</strong> nouvelle ({@link #familyId}).
 * Chaque refresh successif rotate : on marque le précédent {@code rotatedAt}
 * et on insère un nouveau token dans la même famille. Si un token déjà
 * rotaté est présenté à nouveau, c'est qu'il a été volé OU rejoué — on
 * révoque toute la famille (cf. RFC 6749 §10.4, OAuth 2.0 Threat Model).</p>
 *
 * <p>Un token n'est jamais supprimé physiquement à l'usage normal — on le
 * marque {@code rotatedAt} et il sert d'empreinte pour la détection de
 * reuse. Un job de purge nettoie ceux qui sont rotated/revoked depuis
 * plus de 7 jours, ou expirés depuis plus de 7 jours.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.REFRESH_TOKENS)
public class RefreshTokenEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    /** SHA-256 hex du token aléatoire fourni au client. Unique. */
    public String tokenHash;

    /** Owner du token. */
    public UUID userId;

    /** Tenant du user au moment du login. Utile pour cleanup tenant-scoped. */
    public UUID tenantId;

    /**
     * Identifiant de la chaîne de rotation. Tous les tokens issus du même
     * login (et de ses rotations successives) partagent le même familyId.
     * En cas de reuse detection, on révoque toute la famille en une requête.
     */
    public UUID familyId;

    public Instant issuedAt;
    public Instant expiresAt;

    /**
     * Renseigné quand ce token a été échangé contre un nouveau lors d'un
     * {@code /auth/refresh}. Une nouvelle présentation après cette date =
     * reuse detection.
     */
    public Instant rotatedAt;

    /** Renseigné au logout, à la révocation explicite, ou suite à une détection de reuse. */
    public Instant revokedAt;

    /**
     * Raison de la révocation pour debug / audit : {@code "logout"},
     * {@code "reuse_detected"}, {@code "user_disabled"},
     * {@code "expired_cleanup"}.
     */
    public String revokedReason;

    /** User-Agent du client au moment de l'émission. Optionnel, pour "mes sessions". */
    public String userAgent;
    /** IP du client au moment de l'émission. Optionnel. */
    public String ipAddress;

    public RefreshTokenEntity() {}

    public boolean isUsable(Instant now) {
        return revokedAt == null && rotatedAt == null && expiresAt.isAfter(now);
    }
}
