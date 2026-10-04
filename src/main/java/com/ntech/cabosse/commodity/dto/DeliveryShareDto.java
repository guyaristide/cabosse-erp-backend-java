package com.ntech.cabosse.commodity.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * La répartition des livraisons, par client et par label.
 *
 * <p>Le conseil d'administration tient ce tableau à la main depuis des
 * années : qui a pris quoi, et dans quelle proportion (demandé le
 * 03/10/2026). Il se lit par campagne, ou sur plusieurs cumulées, une
 * saison se jouant en une principale et ses intermédiaires.</p>
 *
 * <p>Le volume retenu est le <strong>poids accepté</strong> par le
 * client, celui qui sert de base à la facturation. Le poids déclaré au
 * départ dirait ce qui est parti, pas ce qui a été reconnu.</p>
 *
 * @param campaignIds campagnes retenues ; vide, toute l'histoire
 * @param articleId   type de produit retenu, ou absent pour tous
 * @param byCustomer  une ligne par client, la plus grosse d'abord
 * @param byLabel     une ligne par label de certification
 * @param totalWeight total des volumes, celui sur lequel les parts sont
 *                    calculées
 * @param saleCount   nombre d'expéditions derrière ces volumes
 */
public record DeliveryShareDto(
        List<UUID> campaignIds,
        UUID articleId,
        String unit,
        List<DeliveryShareRowDto> byCustomer,
        List<DeliveryShareRowDto> byLabel,
        BigDecimal totalWeight,
        int saleCount
) {}
