package com.ntech.cabosse.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Génère et hashe des tokens d'invitation. Le token clair est envoyé par
 * mail au destinataire ; seul son hash est stocké côté base, ce qui rend
 * un dump de base inutilisable pour usurper une invitation.
 *
 * <p>Format du token : 32 octets aléatoires (256 bits) encodés en
 * base64url (sans padding). Soit ~43 caractères URL-safe.</p>
 *
 * <p>Hash : SHA-256 hex — déterministe, vérifiable à l'activation en
 * comparant {@code sha256(clientToken) == storedHash}.</p>
 */
@ApplicationScoped
public class InvitationTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Token paire : la valeur claire à envoyer + le hash à stocker en base. */
    public record InvitationToken(String clearValue, String hash) {}

    public InvitationToken generate() {
        byte[] random = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(random);
        String clearValue = ENCODER.encodeToString(random);
        String hash = sha256Hex(clearValue);
        return new InvitationToken(clearValue, hash);
    }

    /** Hash une valeur fournie par un client pour la comparer au hash stocké. */
    public String hashOf(String clearToken) {
        return sha256Hex(clearToken);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 fait partie du JCE standard, ne devrait jamais arriver.
            throw new IllegalStateException("SHA-256 indisponible dans la JVM", e);
        }
    }
}
