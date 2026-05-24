package com.ntech.cabosse.shared.persistence;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

/**
 * Générateur d'identifiants UUID v7 (time-ordered). UUID v7 est :
 * <ul>
 *   <li>48 bits de timestamp ms en préfixe → bonne localité d'écriture
 *       sur l'index {@code _id} (peu de B-tree splits sur les inserts récents),</li>
 *   <li>74 bits aléatoires en suffixe → opacité publique garantie.</li>
 * </ul>
 *
 * Tous les services et repositories qui créent des entités injectent ce
 * générateur. Appel à {@code UUID.randomUUID()} direct interdit
 * (cf. CLAUDE.md §6.2 et multi-tenant-architecture.md §4.1).
 */
@ApplicationScoped
public class IdGenerator {

    /** Génère un nouvel identifiant UUID v7. */
    public UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
