package com.ntech.cabosse.tenant.entity;

/**
 * Contact principal du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>Ce contact est différent de l'admin tenant (qui reçoit l'invitation
 * et a un compte utilisateur). Le contact principal est une référence
 * commerciale/administrative ; il peut être identique à l'admin ou non.</p>
 */
public class TenantContact {

    /** Fonction / libellé du contact (ex. {@code "Direction"}), backlog COOP-05. */
    public String role;
    public String name;
    public String email;
    public String phone;

    /**
     * Contact principal du tenant (affiché par défaut, repris dans les
     * exports/registre), backlog COOP-05. Un seul contact principal.
     */
    public boolean isPrimary;

    public TenantContact() {}
}
