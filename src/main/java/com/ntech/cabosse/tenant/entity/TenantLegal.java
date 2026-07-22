package com.ntech.cabosse.tenant.entity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    /** Sigle / abréviation informatif (backlog COOP-04), ex. {@code "COOPACA"}. */
    public String sigle;

    /**
     * Date de constitution juridique de la structure (backlog COOP-04).
     * Distincte de {@code TenantEntity.createdAt} (date d'ouverture du compte).
     */
    public LocalDate constitutedAt;

    /** Agréments / licences cumulables (backlog COOP-04). */
    public List<TenantAgrement> agrements = new ArrayList<>();

    public TenantLegal() {}
}
