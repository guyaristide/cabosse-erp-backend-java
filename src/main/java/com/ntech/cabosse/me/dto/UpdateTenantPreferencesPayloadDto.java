package com.ntech.cabosse.me.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Payload PATCH des préférences opérationnelles du tenant courant —
 * {@code PUT /api/v1/me/tenant/preferences}.
 *
 * <p>Tous les champs sont nullables : un champ absent signifie « ne pas
 * modifier ». Cela permet au front d'éditer un seul flag (par exemple
 * {@code vatRecoverable}) sans avoir à renvoyer la totalité du payload
 * préférences.</p>
 *
 * <p>Au MVP, seul {@code vatRecoverable} est éditable par cet endpoint.
 * Les autres champs (currency, language, timezone) restent gérés depuis
 * le back-office plateforme via {@code TenantUpdateService} — leur
 * exposition ici se fera quand la fiche tenant côté admin tenant les
 * couvrira.</p>
 */
@Schema(description = "PATCH des préférences tenant — null = ne pas modifier")
public record UpdateTenantPreferencesPayloadDto(

        @Schema(description = "Si vrai (défaut métier), l'entreprise récupère la TVA sur ses achats. "
                + "Si faux, la TVA des achats devient une charge incorporée au CMUP.",
                example = "false")
        Boolean vatRecoverable

) {}
