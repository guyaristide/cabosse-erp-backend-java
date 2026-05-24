package com.ntech.cabosse.auth.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Hashage et vérification de mots de passe via BCrypt.
 *
 * <p>BCrypt avec log_rounds = 12 (≈ 250 ms par hash sur CPU moderne) :
 * suffisant pour ralentir significativement les attaques par force brute
 * sur des dumps de base, sans rendre un login interactif lent.
 * Augmenter à 13 ou 14 si la machine cible est plus puissante.</p>
 *
 * <p>Jamais de mot de passe en clair dans les logs, en base, ou dans une
 * exception (cf. CLAUDE.md §13.1).</p>
 */
@ApplicationScoped
public class PasswordHasher {

    private static final int LOG_ROUNDS = 12;

    public String hash(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Mot de passe vide");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(LOG_ROUNDS));
    }

    public boolean verify(String plainPassword, String hash) {
        if (plainPassword == null || hash == null || hash.isEmpty()) return false;
        try {
            return BCrypt.checkpw(plainPassword, hash);
        } catch (RuntimeException e) {
            // Hash mal formé (data corrompue, ancien format, troncature) →
            // refus silencieux. jBCrypt peut lever IllegalArgumentException
            // ou StringIndexOutOfBoundsException selon le défaut.
            return false;
        }
    }
}
