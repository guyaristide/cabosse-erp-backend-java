package com.ntech.cabosse.tenant.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDate;

/**
 * Le passage quotidien sur les licences.
 *
 * <p>Une fois par jour suffit : une échéance se compte en jours, et rien
 * ne justifie de suspendre une structure à trois heures près. Le réveil
 * est fixé à l'aube, avant que les équipes n'arrivent, pour qu'une
 * suspension se découvre au premier accès et non en pleine saisie.</p>
 *
 * <p>Le travail vit dans {@link LicenseExpiryService}, que les tests
 * appellent directement : un test qui dépendrait du planificateur
 * dépendrait de l'horloge.</p>
 */
@ApplicationScoped
public class LicenseExpiryScheduler {

    @Inject LicenseExpiryService licenses;
    @Inject Logger log;

    @Scheduled(cron = "{cabosse.license.sweep-cron:0 10 4 * * ?}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        ExpirySweep result = licenses.sweep(LocalDate.now());
        if (result.noticed() > 0 || result.suspended() > 0) {
            log.infof("Licences : %d avertissement(s), %d suspension(s)",
                    result.noticed(), result.suspended());
        }
    }
}
