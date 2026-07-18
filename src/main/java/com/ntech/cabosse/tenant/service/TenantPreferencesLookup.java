package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Lecture des préférences opérationnelles du tenant courant depuis les
 * services métier. Retourne toujours un objet non nul : un tenant sans
 * préférences hydratées reçoit les défauts portés par les getters de
 * {@link TenantPreferences} (même philosophie que {@code vatRecoverable}).
 */
@ApplicationScoped
public class TenantPreferencesLookup {

    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;

    public TenantPreferences current() {
        TenantEntity t = tenants.findById(tenantContext.tenantId());
        return t != null && t.preferences != null ? t.preferences : new TenantPreferences();
    }
}
