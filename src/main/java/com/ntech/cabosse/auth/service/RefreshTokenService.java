package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.auth.entity.RefreshTokenEntity;
import com.ntech.cabosse.auth.repository.RefreshTokenRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
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
 *
 * <p><strong>Aucune transaction multi-documents ici.</strong> Toutes les
 * écritures sont des {@code updateOne} conditionnels mono-document,
 * atomiques par construction côté MongoDB : deux requêtes concurrentes
 * sur le même token (logout pendant un refresh, double clic, deux
 * onglets) se sérialisent sur le document. Les anciennes versions
 * transactionnelles (read-modify-replace sous {@code @Transactional})
 * provoquaient des {@code WriteConflict} transitoires → 500 en prod.</p>
 */
@ApplicationScoped
public class RefreshTokenService {

    /** TTL d'un refresh token. 30 jours = compromis confort / fenêtre de fuite. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    @Inject RefreshTokenRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject UserRepository users;
    @Inject AuditService audit;
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
    public IssuedRefreshToken issueNew(UUID userId, UUID tenantId, String userAgent, String ipAddress) {
        return persistNew(userId, tenantId, idGenerator.newId(), userAgent, ipAddress);
    }

    /**
     * Échange un refresh token valide contre un nouveau. Détecte les rejeux
     * et révoque la famille en cas de fraude.
     *
     * @throws UnauthorizedException si le token est inconnu, expiré, révoqué
     *                                ou s'il s'agit d'un rejeu d'un token
     *                                déjà rotaté (y compris une rotation
     *                                concurrente perdue).
     */
    public RotatedRefresh rotate(String presentedSecret, String userAgent, String ipAddress) {
        Instant now = Instant.now();
        RefreshTokenEntity old = repo.findByHash(hash(presentedSecret))
                .orElseThrow(() -> new UnauthorizedException(Messages.msg("m.aut-refresh-token-invalid")));

        if (old.revokedAt != null) {
            throw new UnauthorizedException(Messages.msg("m.aut-refresh-token-revoked"));
        }
        if (old.expiresAt.isBefore(now)) {
            throw new UnauthorizedException(Messages.msg("m.aut-refresh-token-expired"));
        }
        if (old.rotatedAt != null) {
            // Reuse detected — révocation en cascade de toute la famille.
            revokeFamilyForReuse(old, now, ipAddress);
            throw new UnauthorizedException(Messages.msg("m.aut-refresh-token-reused"));
        }

        // Point de sérialisation : une seule rotation gagne l'updateOne
        // conditionnel. Un perdant signifie qu'un autre appel vient de
        // rotater (ou révoquer) ce token entre notre lecture et ici —
        // même traitement qu'un rejeu : la famille est révoquée.
        if (!repo.markRotated(old.id, now)) {
            revokeFamilyForReuse(old, now, ipAddress);
            throw new UnauthorizedException(Messages.msg("m.aut-refresh-token-reused"));
        }

        return new RotatedRefresh(
                persistNew(old.userId, old.tenantId, old.familyId, userAgent, ipAddress),
                old.userId,
                old.tenantId
        );
    }

    /**
     * Un rejeu est un vol de jeton ou une file qui a doublé : dans les deux
     * cas la structure doit le voir dans son journal (SEC-16). Le
     * rafraîchissement ordinaire, lui, n'y figure pas : il en remplirait
     * une ligne toutes les quinze minutes par session.
     */
    private void revokeFamilyForReuse(RefreshTokenEntity token, Instant now, String ipAddress) {
        long affected = repo.revokeFamily(token.familyId, now, "reuse_detected");
        log.warnf("Refresh token reuse detected (familyId=%s, userId=%s) : %d tokens revoked",
                token.familyId, token.userId, affected);
        UserEntity user = users.findById(token.userId);
        audit.event(AuditEventType.AUTHENTICATION_FAILED)
                .actor(user != null ? user.email : null, token.userId)
                .target("refresh_token_family", token.familyId.toString(), null)
                .tenant(token.tenantId, null)
                .description("Jeton de rafraîchissement réutilisé, famille révoquée")
                .payload(Map.of("reason", "refresh_token_reuse", "revokedTokens", affected))
                .ip(ipAddress)
                .record();
    }

    public record RotatedRefresh(IssuedRefreshToken token, UUID userId, UUID tenantId) {}

    /**
     * Révoque un refresh token (logout). Idempotent — silencieux si le
     * token est introuvable ou déjà révoqué. Écriture conditionnelle
     * mono-document : aucun conflit possible avec un refresh ou un autre
     * logout simultané.
     *
     * @return {@code true} si cet appel a effectivement révoqué le jeton.
     */
    public boolean revoke(String presentedSecret, String reason) {
        return repo.revokeByHash(hash(presentedSecret), Instant.now(), reason);
    }

    /** Le jeton tel qu'il est en base, sans le toucher : pour savoir à qui il appartient. */
    public Optional<RefreshTokenEntity> peek(String presentedSecret) {
        if (presentedSecret == null || presentedSecret.isBlank()) return Optional.empty();
        return repo.findByHash(hash(presentedSecret));
    }

    /**
     * Coupe toutes les sessions d'un compte, sur tous ses appareils.
     * C'est le geste qui accompagne une désactivation : sans lui, le
     * compte désactivé se rafraîchirait encore pendant trente jours.
     */
    public void revokeAllForUser(UUID userId, String reason) {
        long n = repo.revokeAllForUser(userId, Instant.now(), reason);
        if (n > 0) log.infof("Revoked %d refresh token(s) of user %s (%s)", n, userId, reason);
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
