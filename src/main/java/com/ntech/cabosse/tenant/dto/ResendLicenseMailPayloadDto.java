package com.ntech.cabosse.tenant.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * À qui renvoyer la confirmation de licence.
 *
 * <p>Un courrier se perd, une adresse change, un interlocuteur arrive
 * après coup : le renvoyer ne doit pas obliger à réactiver la licence
 * (demandé le 04/10/2026).</p>
 */
@Schema(description = "Renvoi de la confirmation de licence")
public record ResendLicenseMailPayloadDto(
        @Schema(description = "Adresses, parmi les comptes de la structure, à qui renvoyer.")
        List<String> notifyEmails,

        /**
         * Adresses saisies à la main : un comptable externe ou un
         * directeur financier n'a pas toujours de compte, et le courrier
         * doit quand même pouvoir l'atteindre (05/10/2026).
         */
        @Schema(description = "Adresses libres, hors comptes de la structure.")
        List<String> extraEmails
) {}
