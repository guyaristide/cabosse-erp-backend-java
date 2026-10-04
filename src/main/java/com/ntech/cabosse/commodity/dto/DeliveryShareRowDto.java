package com.ntech.cabosse.commodity.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ce qu'une ligne pèse dans les livraisons de la période.
 *
 * <p>Sert aux deux répartitions, par client et par label : ce sont deux
 * lectures du même volume, et deux formes de ligne auraient fini par
 * diverger sur l'arrondi du pourcentage.</p>
 *
 * @param key    identifiant du client, ou code du label quand il s'en
 *               reconnaît un au référentiel. Absent pour un label saisi
 *               à la main.
 * @param label  ce qui s'affiche
 * @param weight volume livré, au poids accepté par le client
 * @param share  part du total, en pourcent
 */
public record DeliveryShareRowDto(
        UUID key,
        String code,
        String label,
        BigDecimal weight,
        BigDecimal share
) {}
