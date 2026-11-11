package com.ntech.cabosse.tenant.event;

import com.ntech.cabosse.shared.events.Events;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.tenant.service.TenantUserService;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.repository.UserRepository;
import com.ntech.cabosse.user.service.MembershipService;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Envoie, hors de la transaction de provisionnement, le mot d'accueil à
 * l'administrateur déjà connu d'une structure neuve (SAAS-29). Un échec
 * d'envoi ne défait rien : la porte est ouverte, il la verra au prochain
 * passage.
 */
@ApplicationScoped
public class TenantAdminAdoptedListener {

    @Inject TenantUserService tenantUsers;
    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject MembershipService memberships;
    @Inject Logger log;

    @ConsumeEvent(Events.TENANT_ADMIN_ADOPTED)
    @Blocking
    public void onAdminAdopted(TenantAdminAdoptedEvent event) {
        try {
            TenantEntity tenant = tenants.findById(event.tenantId());
            UserEntity admin = users.findById(event.adminUserId());
            if (tenant == null || admin == null) return;
            tenantUsers.sendAddedToTenantMail(tenant, admin,
                    memberships.resolve(admin, tenant.id).orElse(null));
            log.infof("Mot d'accueil envoyé à %s pour le tenant %s", event.adminEmail(), event.tenantId());
        } catch (Exception e) {
            log.errorf(e, "Échec envoi du mot d'accueil à %s (tenant %s)",
                    event.adminEmail(), event.tenantId());
        }
    }
}
