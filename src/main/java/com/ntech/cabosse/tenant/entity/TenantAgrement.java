package com.ntech.cabosse.tenant.entity;

/**
 * Agrément / licence de la coopérative. Sub-document de {@link TenantLegal},
 * embarqué dans la liste {@code agrements} (backlog COOP-04).
 *
 * <p>Liste extensible plutôt qu'un champ unique en dur : une structure peut
 * cumuler plusieurs agréments (filière, faîtière, programme de certification…).
 * Le {@code type} porte le libellé (ex. « Agrément CCC »), le {@code number}
 * le numéro délivré. Rester agnostique filière : aucun type n'est imposé.</p>
 */
public class TenantAgrement {

    /** Libellé du type d'agrément (ex. {@code "Agrément CCC"}). */
    public String type;

    /** Numéro de l'agrément tel que délivré. */
    public String number;

    public TenantAgrement() {}

    public TenantAgrement(String type, String number) {
        this.type = type;
        this.number = number;
    }
}
