package com.ntech.cabosse.tenant.event;

import java.util.UUID;

/**
 * Événement publié quand un tenant vient d'être provisionné avec succès
 * (transition {@code PROVISIONING → ACTIVE}). Consommé par
 * {@link TenantInvitationListener} qui envoie le mail d'invitation à
 * l'admin du tenant.
 *
 * <p>Le token d'invitation est embarqué <strong>en clair</strong> dans
 * l'événement parce qu'il vit en mémoire JVM le temps du dispatch et
 * ne traverse pas la base. Le mail le transmet une fois ; ensuite seul
 * son hash est conservé côté user.</p>
 */
public record TenantProvisionedEvent(
        UUID tenantId,
        String tenantName,
        UUID adminUserId,
        String adminEmail,
        String adminFirstName,
        String invitationTokenClearValue
) {}
