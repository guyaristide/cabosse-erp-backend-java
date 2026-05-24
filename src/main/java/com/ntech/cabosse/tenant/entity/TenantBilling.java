package com.ntech.cabosse.tenant.entity;

/**
 * Coordonnées de facturation du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>L'e-mail de facturation peut différer du contact principal — typiquement
 * un alias comptabilité ({@code finance@…}).</p>
 */
public class TenantBilling {

    public String email;
    public BillingCycle cycle;

    public TenantBilling() {}
}
