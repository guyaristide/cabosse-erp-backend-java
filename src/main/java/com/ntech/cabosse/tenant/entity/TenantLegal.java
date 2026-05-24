package com.ntech.cabosse.tenant.entity;

/**
 * Informations légales du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>RCCM = Registre du Commerce et du Crédit Mobilier (zone OHADA).
 * Le NIF (NCC en Côte d'Ivoire) est l'identifiant fiscal local. La TVA
 * intra n'est applicable que pour les transactions hors zone OHADA.</p>
 */
public class TenantLegal {

    public String legalName;
    public LegalForm legalForm;
    public String rccm;
    public String taxId;
    /** Optionnel — la plupart des tenants n'en ont pas. */
    public String vatNumber;

    public TenantLegal() {}
}
