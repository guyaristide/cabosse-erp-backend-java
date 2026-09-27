package com.ntech.cabosse.auth.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ralentit les tentatives de connexion répétées (relevé le 27/09/2026).
 *
 * <p>Six essais enchaînés sur le serveur en ligne n'ont rien déclenché :
 * rien n'empêchait d'essayer un mot de passe après l'autre. Le reste de
 * l'authentification tient bien, notamment l'absence d'énumération de
 * comptes, mais sans frein la seule limite était le débit du réseau.</p>
 *
 * <p>Le compte se tient par <strong>adresse e-mail et par IP à la
 * fois</strong>. Par e-mail seul, un attaquant changerait d'adresse à
 * chaque essai ; par IP seule, il suffirait d'un réseau partagé pour
 * bloquer des collègues innocents. Les deux compteurs coexistent, et le
 * premier atteint ferme la porte.</p>
 *
 * <p>Le refus ne dit pas si le compte existe : il tombe de la même façon
 * sur une adresse inventée. Sans cela, le frein rendrait l'énumération
 * possible alors que la connexion s'en garde.</p>
 *
 * <p>Compteurs en mémoire, donc propres à une instance. C'est suffisant
 * tant qu'un seul serveur tourne ; le jour où il y en aura plusieurs,
 * ils devront passer en base ou dans un cache partagé, sinon la limite
 * se divise par le nombre d'instances.</p>
 */
@ApplicationScoped
public class LoginThrottle {

    /** Au delà, on refuse. Assez large pour une faute de frappe répétée. */
    static final int MAX_ATTEMPTS = 8;

    /** Fenêtre glissante d'observation des échecs. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    /** Durée du refus une fois la limite atteinte. */
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    /** Au delà, on purge : les compteurs ne doivent pas grossir sans fin. */
    private static final int MAX_TRACKED = 10_000;

    private final Map<String, Attempts> byKey = new ConcurrentHashMap<>();

    /** Ce qu'on retient d'une clé : ses échecs récents, et jusqu'à quand elle est fermée. */
    private static final class Attempts {
        int count;
        Instant first;
        Instant blockedUntil;
    }

    /**
     * Combien de temps il reste à attendre, ou zéro si la voie est libre.
     *
     * <p>Rendre la durée plutôt qu'un booléen permet de la dire à
     * l'appelant : un refus sans échéance laisse réessayer au hasard.</p>
     */
    public Duration retryAfter(String email, String ip) {
        Instant now = Instant.now();
        Duration longest = Duration.ZERO;
        for (String key : keysOf(email, ip)) {
            Attempts a = byKey.get(key);
            if (a == null || a.blockedUntil == null) continue;
            if (a.blockedUntil.isAfter(now)) {
                Duration left = Duration.between(now, a.blockedUntil);
                if (left.compareTo(longest) > 0) longest = left;
            }
        }
        return longest;
    }

    /** Un échec de plus. Ferme la porte quand la limite est atteinte. */
    public void recordFailure(String email, String ip) {
        Instant now = Instant.now();
        purgeIfCrowded(now);
        for (String key : keysOf(email, ip)) {
            byKey.compute(key, (k, existing) -> {
                Attempts a = existing;
                // Fenêtre expirée : on repart de zéro plutôt que de
                // cumuler des échecs vieux d'une journée.
                if (a == null || a.first == null || a.first.plus(WINDOW).isBefore(now)) {
                    a = new Attempts();
                    a.first = now;
                    a.count = 0;
                }
                a.count++;
                if (a.count >= MAX_ATTEMPTS) {
                    a.blockedUntil = now.plus(LOCKOUT);
                    a.count = 0;
                    a.first = now;
                }
                return a;
            });
        }
    }

    /** Connexion réussie : la personne n'est pas un attaquant, on oublie. */
    public void recordSuccess(String email, String ip) {
        for (String key : keysOf(email, ip)) {
            byKey.remove(key);
        }
    }

    private static String[] keysOf(String email, String ip) {
        String e = email == null ? "" : email.trim().toLowerCase();
        String i = ip == null ? "" : ip.trim();
        if (e.isEmpty()) return new String[] { "ip:" + i };
        if (i.isEmpty()) return new String[] { "email:" + e };
        return new String[] { "email:" + e, "ip:" + i };
    }

    /**
     * Empêche la table de grossir sans fin.
     *
     * <p>Une adresse inventée à chaque essai créerait une entrée à
     * chaque essai : le frein deviendrait lui-même le moyen d'épuiser la
     * mémoire du serveur.</p>
     */
    private void purgeIfCrowded(Instant now) {
        if (byKey.size() < MAX_TRACKED) return;
        byKey.values().removeIf(a ->
                (a.blockedUntil == null || a.blockedUntil.isBefore(now))
                        && (a.first == null || a.first.plus(WINDOW).isBefore(now)));
    }
}
