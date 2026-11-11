package com.ntech.cabosse.tenant.event;

import java.util.UUID;

/**
 * Un compte déjà actif vient d'être fait administrateur d'une structure
 * neuve (SAAS-29). Consommé par {@link TenantAdminAdoptedListener}, qui
 * lui écrit que la porte est ouverte : pas de lien d'activation, son mot
 * de passe est déjà le sien.
 */
public record TenantAdminAdoptedEvent(
        UUID tenantId,
        String tenantName,
        UUID adminUserId,
        String adminEmail,
        String adminFirstName,
        String locale
) {}
