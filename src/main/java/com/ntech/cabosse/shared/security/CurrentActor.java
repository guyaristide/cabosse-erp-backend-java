package com.ntech.cabosse.shared.security;

import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Qui agit, sous un nom lisible.
 *
 * <p>Les états remis au conseil nomment les personnes : « la caissière »,
 * pas une adresse électronique. Le nom se fige au moment du geste, jamais
 * ne se résout après coup : un compte désactivé ou renommé rendrait
 * illisible une trace vieille de six mois.</p>
 */
@ApplicationScoped
public class CurrentActor {

    @Inject JsonWebToken jwt;
    @Inject UserRepository users;

    /** Adresse de l'appelant, ou {@code null} hors d'une requête portée. */
    public String email() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    /**
     * Nom et prénom de l'appelant, son adresse à défaut.
     *
     * <p>L'adresse plutôt que rien : une trace anonyme vaut moins qu'une
     * trace imparfaite.</p>
     */
    public String name() {
        String email = email();
        if (email == null) return null;
        return users.findByEmail(email)
                .map(u -> {
                    String full = ((u.firstName != null ? u.firstName : "") + " "
                            + (u.lastName != null ? u.lastName : "")).trim();
                    return full.isEmpty() ? email : full;
                })
                .orElse(email);
    }
}
