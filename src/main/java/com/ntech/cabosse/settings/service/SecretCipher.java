package com.ntech.cabosse.settings.service;

import com.ntech.cabosse.shared.config.ApplicationConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement AES-GCM des valeurs sensibles (mots de passe SMTP, API keys
 * paiement, secrets stockage). Clé attendue dans la variable d'environnement
 * {@code PLATFORM_SETTINGS_ENCRYPTION_KEY} (base64, 32 bytes après décodage
 * = AES-256).
 *
 * <p><strong>Format du ciphertext encodé :</strong>
 * {@code base64( iv[12 bytes] || ciphertext+tag )}. Au déchiffrement, on
 * split les 12 premiers bytes pour l'IV. Le tag d'authentification (16
 * bytes) est inclus à la fin du ciphertext par le mode GCM.</p>
 *
 * <p>Aucune clé en dur, aucun fallback — si la variable manque ou est mal
 * formée, le service échoue au démarrage. Volontaire : un secret en clair
 * en BD n'est pas un MVP, c'est une fuite imminente.</p>
 */
@ApplicationScoped
public class SecretCipher {

    private static final String ALG = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;        // recommandé pour GCM
    private static final int TAG_LENGTH_BITS = 128; // tag d'authentification

    @jakarta.inject.Inject ApplicationConfig appConfig;
    @jakarta.inject.Inject Logger log;

    private SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    @PostConstruct
    void init() {
        String base64Key = appConfig.platformSettings().encryptionKey().orElse(null);
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "PLATFORM_SETTINGS_ENCRYPTION_KEY manquante : impossible de démarrer " +
                    "tant que la clé de chiffrement des paramètres plateforme n'est pas " +
                    "configurée. Générer avec : openssl rand -base64 32"
            );
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PLATFORM_SETTINGS_ENCRYPTION_KEY invalide (pas du base64).", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "PLATFORM_SETTINGS_ENCRYPTION_KEY doit faire 32 bytes après décodage base64 (AES-256), " +
                    "obtenu : " + raw.length + " bytes."
            );
        }
        this.key = new SecretKeySpec(raw, ALG);
        log.info("SecretCipher initialisé (AES-256-GCM).");
    }

    /** Chiffre une valeur en clair et renvoie la représentation base64. */
    public String encrypt(String clear) {
        if (clear == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(clear.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("Échec du chiffrement.", e);
        }
    }

    /** Déchiffre une valeur produite par {@link #encrypt(String)}. */
    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length < IV_LENGTH + 16) {
                throw new IllegalStateException("Ciphertext trop court.");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Échec du déchiffrement (clé incorrecte ou données altérées).", e);
        }
    }

    /**
     * Masque une valeur pour affichage admin : {@code ••••••••} + 4 derniers
     * caractères. Renvoie {@code "•"} si la valeur fait moins de 4 caractères.
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 4) return "••••";
        return "••••••••" + value.substring(value.length() - 4);
    }
}
