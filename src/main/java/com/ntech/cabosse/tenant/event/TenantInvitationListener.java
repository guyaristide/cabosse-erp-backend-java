package com.ntech.cabosse.tenant.event;

import com.ntech.cabosse.settings.mail.PlatformMailerService;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.shared.events.Events;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.vertx.ConsumeEvent;
import org.jboss.logging.Logger;

/**
 * Consomme {@link TenantProvisionedEvent} pour envoyer le mail
 * d'invitation à l'admin du tenant.
 *
 * <p>Exécution hors transaction principale : un échec d'envoi ne rollback
 * pas le provisioning (cf. multi-tenant-architecture.md §9.4). L'audit
 * journalise séparément la tentative pour permettre une retentative
 * manuelle ultérieure.</p>
 *
 * <p>Note : on n'a pas besoin de réactiver un contexte tenant ici parce
 * que l'envoi de mail ne touche pas une base tenant-scoped. Si demain
 * on doit charger des données depuis la base tenant (ex. : template
 * personnalisé), ajouter {@code TenantAwareExecutor.runForTenant(...)}.</p>
 *
 * @see EventBus
 */
@ApplicationScoped
public class TenantInvitationListener {

    @Inject
    PlatformMailerService mailer;

    @Inject
    @Location("mail/tenant-invitation.html")
    Template invitationTemplate;

    @Inject ApplicationConfig appConfig;

    @Inject
    Logger log;

    @ConsumeEvent(Events.TENANT_PROVISIONED)
    @Blocking
    public void onTenantProvisioned(TenantProvisionedEvent event) {
        try {
            String activationUrl = String.format(
                    "%s/invitation/%s",
                    appConfig.frontendBaseUrl(),
                    event.invitationTokenClearValue()
            );

            String html = invitationTemplate
                    .data("tenantName", event.tenantName())
                    .data("firstName", event.adminFirstName())
                    .data("activationUrl", activationUrl)
                    .render();

            mailer.sendHtml(
                    event.adminEmail(),
                    "Activez votre compte Cabosse ERP — " + event.tenantName(),
                    html
            );

            log.infof("Invitation envoyée à %s pour le tenant %s",
                    event.adminEmail(), event.tenantId());
        } catch (Exception e) {
            // On ne rejette pas : l'envoi de mail est best-effort. Le job
            // d'audit côté provisioning service a déjà loggué le succès
            // du provisioning ; l'admin peut relancer l'invitation depuis M9.
            log.errorf(e, "Échec envoi invitation à %s (tenant %s)",
                    event.adminEmail(), event.tenantId());
        }
    }
}
