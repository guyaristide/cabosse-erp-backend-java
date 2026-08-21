package com.ntech.cabosse.notification.service;

import com.mongodb.client.model.Filters;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import com.ntech.cabosse.shared.tenant.TenantAwareExecutor;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Relais planifié : passe sur les tenants actifs et draine leur file.
 *
 * <p>Un relais <strong>par canal</strong>, chacun avec sa propre
 * planification. C'est délibéré : quand une passerelle SMTP ne répond
 * plus, ses envois occupent le temps de chaque passage, et une file
 * commune retiendrait les SMS derrière eux.</p>
 *
 * <p>{@code concurrentExecution = SKIP} : si un passage déborde sur le
 * suivant, le suivant est sauté plutôt qu'exécuté en parallèle. Les
 * lignes ne seraient de toute façon pas envoyées deux fois (la prise est
 * atomique), mais empiler des passages sur une passerelle lente ne fait
 * qu'aggraver la situation.</p>
 */
@ApplicationScoped
public class NotificationRelay {

    private static final List<String> RELAYED_STATUSES = List.of(
            TenantStatus.ACTIVE.name(),
            // Un tenant suspendu garde ses envois transactionnels (une
            // réinitialisation de mot de passe reste légitime) ; le tri fin
            // par usage se fera avec les préférences.
            TenantStatus.SUSPENDED.name()
    );

    @Inject ControlPlaneProvider controlPlane;
    @Inject TenantAwareExecutor executor;
    @Inject DeliveryDrainer drainer;
    @Inject ProviderResolver resolver;
    @Inject Logger log;

    @Scheduled(every = "{application.notifications.email-relay-interval:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            delayed = "20s")
    void relayEmail() {
        relay(NotificationChannel.EMAIL);
    }

    @Scheduled(every = "{application.notifications.sms-relay-interval:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            delayed = "25s")
    void relaySms() {
        relay(NotificationChannel.SMS);
    }

    /**
     * Draine un canal pour tous les tenants relayés. Public pour que les
     * tests pilotent un passage sans dépendre de l'horloge du planificateur.
     */
    public void relay(NotificationChannel channel) {
        // Aucune passerelle configurée sur ce canal : inutile d'ouvrir un
        // contexte par tenant pour ne rien faire. Une lecture, puis on sort.
        if (!resolver.channelHasActiveProvider(channel)) return;

        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (TenantEntity tenant : relayedTenants()) {
            try {
                executor.runForTenant(tenant.id, tenant.databaseName, () -> {
                    DeliveryDrainer.DrainReport report = drainer.drain(channel);
                    sent.addAndGet(report.sent());
                    failed.addAndGet(report.failed());
                });
            } catch (Exception e) {
                // Un tenant en défaut ne doit pas priver les autres de leur
                // relais : on trace et on continue la boucle.
                log.errorf(e, "Relais %s en échec pour le tenant %s", channel, tenant.slug);
            }
        }
        if (sent.get() > 0 || failed.get() > 0) {
            log.infof("Relais %s : %d envoyée(s), %d abandonnée(s)",
                    channel, sent.get(), failed.get());
        }
    }

    private List<TenantEntity> relayedTenants() {
        List<TenantEntity> tenants = new ArrayList<>();
        controlPlane.collection(ControlPlane.Collections.TENANTS, TenantEntity.class)
                .find(Filters.in("status", RELAYED_STATUSES))
                .forEach(tenants::add);
        return tenants;
    }
}
