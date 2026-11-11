package com.ntech.cabosse.user.service;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Au démarrage, chaque compte d'avant la mise à jour reçoit l'appartenance
 * à sa structure d'origine (SAAS-29). Après les index du plan contrôle,
 * avant l'amorçage de l'administrateur de plateforme.
 *
 * <p>Pas de Mongock pour le plan contrôle : un observateur rejouable
 * suffit, comme pour les index. En profil test, la base est vidée avant
 * chaque test et l'appartenance se sous-entend à la lecture.</p>
 */
@ApplicationScoped
public class MembershipBackfill {

    @Inject MembershipService memberships;
    @Inject Logger log;

    void onStart(@Observes @Priority(2550) StartupEvent ev) {
        if (LaunchMode.current() == LaunchMode.TEST) return;
        try {
            memberships.backfill();
        } catch (RuntimeException e) {
            // Une reprise qui échoue ne doit pas empêcher le serveur de
            // démarrer : l'appartenance se sous-entend encore à la lecture.
            log.warn("Reprise des appartenances impossible au démarrage", e);
        }
    }
}
