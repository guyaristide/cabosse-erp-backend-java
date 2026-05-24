package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.auth.entity.RefreshTokenEntity;
import com.ntech.cabosse.auth.repository.RefreshTokenRepository;
import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Émission, rotation et révocation des refresh tokens.
 *
 * <p>Le secret côté client est une chaîne aléatoire de 32 bytes encodée
 * en base64url (≈43 caractères). En base on stocke uniquement son
 * {@code SHA-256} hex — un dump de la collection ne permet pas de
 * réutiliser les tokens.</p>
 *
 * <p>Sur chaque {@link #rotate}, le précédent token est marqué
 * {@code rotatedAt} et un nouveau est inséré dans la même famille. Si un
 * token déjà rotaté est représenté, c'est qu'il a été volé OU rejoué : on
 * révoque toute la famille (RFC 6749 §10.4 — refresh token reuse).</p>
 */
@ApplicationScoped
public class RefreshTokenService {

    /** TTL d'un refresh token. 30 jours = compromis confort / fenêtre de fuite. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    @Inject RefreshTokenRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject Logger log;

    /** Token freshly minted. {@link #secret} ne quitte la mémoire qu'une fois. */
    public record IssuedRefreshToken(
            String secret,
            UUID id,
            UUID familyId,
            Instant expiresAt
    ) {}

    /**
     * Émet un nouveau refresh token pour un user (cas login). Démarre une
     * nouvelle famille de rotation.
     */
    @Transactional
    public IssuedRefreshToken issueNew(UUID userId, UUID tenantId, String userAgent, String ipAddress) {
        return persistNew(userId, tenantId, idGenerator.newId(), userAgent, ipAddress);
    }

    /**
     * Échange un refresh token valide contre un nouveau. Détecte les rejeux
     * et révoque la famille en cas de fraude.
     *
     * <p>{@code dontRollbackOn = UnauthorizedException} pour garantir que
     * la révocation cascade (cas reuse) reste persistée même si on throw
     * juste après. Sans ça, l'attaquant et la victime peuvent réutiliser
     * leurs tokens indéfiniment.</p>
     *
     * @throws UnauthorizedException si le token est inconnu, expiré, révoqué
     *                                ou s'il s'agit d'un rejeu d'un token
     *                                déjà rotaté.
     */
    @Transactional(dontRollbackOn = UnauthorizedException.class)
    public RotatedRefresh rotate(String presentedSecret, String userAgent, String ipAddress) {
        Instant now = Instant.now();
        RefreshTokenEntity old = repo.findByHash(hash(presentedSecret))
                .orElseThrow(() -> new UnauthorizedException("Refresh token invalide."));

        if (old.revokedAt != null) {
            throw new UnauthorizedException("Refresh token révoqué.");
        }
        if (old.expiresAt.isBefore(now)) {
            throw new UnauthorizedException("Refresh token expiré.");
        }
        if (old.rotatedAt != null) {
            // Reuse detected — révocation en cascade de toute la famille.
            long affected = repo.revokeFamily(old.familyId, now, "reuse_detected");
            log.warnf("Refresh token reuse detected (familyId=%s, userId=%s) — %d tokens revoked",
                    old.familyId, old.userId, affected);
            throw new UnauthorizedException("Refresh token réutilisé — toutes les sessions ont été invalidées.");
        }

        // Rotation atomique : on marque l'ancien rotated et on insère le nouveau.
        old.rotatedAt = now;
        repo.update(old);
        return new RotatedRefresh(
                persistNew(old.userId, old.tenantId, old.familyId, userAgent, ipAddress),
                old.userId,
                old.tenantId
        );
    }

    public record RotatedRefresh(IssuedRefreshToken token, UUID userId, UUID tenantId) {}

    /** Révoque un refresh token (logout). Idempotent — silencieux si introuvable. */
    @Transactional
    public void revoke(String presentedSecret, String reason) {
        Optional<RefreshTokenEntity> opt = repo.findByHash(hash(presentedSecret));
        if (opt.isEmpty()) return;
        RefreshTokenEntity t = opt.get();
        if (t.revokedAt != null) return;
        t.revokedAt = Instant.now();
        t.revokedReason = reason;
        repo.update(t);
    }

    // ─── Internals ───

    private IssuedRefreshToken persistNew(UUID userId, UUID tenantId, UUID familyId,
                                          String userAgent, String ipAddress) {
        Instant now = Instant.now();
        String secret = randomSecret();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.id = idGenerator.newId();
        entity.tokenHash = hash(secret);
        entity.userId = userId;
        entity.tenantId = tenantId;
        entity.familyId = familyId;
        entity.issuedAt = now;
        entity.expiresAt = now.plus(REFRESH_TOKEN_TTL);
        entity.userAgent = trim(userAgent, 250);
        entity.ipAddress = trim(ipAddress, 64);
        repo.persist(entity);
        return new IssuedRefreshToken(secret, entity.id, entity.familyId, entity.expiresAt);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
