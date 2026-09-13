package com.ntech.cabosse.direction.dto;

import java.math.BigDecimal;

/**
 * Ce qu'un label de certification a pesé dans les ventes de la campagne.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Le label est saisi à la
 * main sur l'expédition : il est rapproché du référentiel des
 * certifications quand il s'y reconnaît, et laissé tel quel sinon. Une
 * vente sans label reste comptée, sous une entrée sans code : la faire
 * disparaître ferait mentir le total.</p>
 */
public record CampaignLabelShareDto(
        /** Code du référentiel quand le label s'y reconnaît, sinon absent. */
        String code,
        /** Le libellé affiché : celui du référentiel, ou la saisie telle quelle. */
        String label,
        BigDecimal soldWeight,
        BigDecimal revenue,
        BigDecimal sharePct
) {}
