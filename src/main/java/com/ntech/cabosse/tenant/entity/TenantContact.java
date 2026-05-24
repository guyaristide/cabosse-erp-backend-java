package com.ntech.cabosse.tenant.entity;

/**
 * Contact principal du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>Ce contact est différent de l'admin tenant (qui reçoit l'invitation
 * et a un compte utilisateur). Le contact principal est une référence
 * commerciale/administrative ; il peut être identique à l'admin ou non.</p>
 */
public class TenantContact {

    public String name;
    public String email;
    public String phone;

    public TenantContact() {}
}
