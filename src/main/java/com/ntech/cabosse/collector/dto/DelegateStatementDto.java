package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * État des délégués sur une ou plusieurs campagnes, une ligne par délégué.
 *
 * <p>L'expert demande trois états qui ne diffèrent que par les colonnes
 * montrées : la mise en compte seule, la marge de fonctionnement seule, les
 * deux ensemble. Ce sont trois lectures d'un même relevé, pas trois
 * calculs : le serveur en produit un, l'écran choisit ce qu'il affiche.</p>
 *
 * <p>Chaque grandeur est donnée deux fois, au kilo et en montant. Le taux
 * est ce qui a été convenu avec le délégué ; le montant est ce que ses
 * livraisons ont réellement produit. Ne montrer que le taux laisserait
 * croire qu'un délégué qui n'a rien livré a coûté autant qu'un autre, et
 * additionner des francs par kilo ne voudrait rien dire, d'où le total
 * porté par les montants seuls.</p>
 *
 * <p>La période est un ensemble de campagnes plutôt qu'un couple
 * « principale / intermédiaire » : la plateforme ne présuppose pas le
 * découpage de campagne d'une filière. Demander les deux campagnes d'une
 * saison revient à passer leurs deux identifiants.</p>
 */
@Schema(description = "État des délégués collecteurs sur une période")
public record DelegateStatementDto(
        List<UUID> campaignIds,
        List<Row> rows,
        Totals totals
) {
    public record Row(
            UUID delegateSupplierId,
            String delegateCode,
            String delegateName,
            String sectionName,
            /** Retenue convenue sur la fiche du délégué, en FCFA/kg. */
            BigDecimal retentionPerKgFcfa,
            /** Retenue réellement figée sur ses reçus de la période. */
            BigDecimal retentionAmountFcfa,
            /** Marge de fonctionnement convenue, en FCFA/kg. */
            BigDecimal marginPerKgFcfa,
            /** Marge réellement figée sur ses reçus de la période. */
            BigDecimal marginAmountFcfa,
            BigDecimal weightKg,
            BigDecimal deliveredFcfa) {}

    /** Totaux de la période. Les taux ne s'additionnent pas : ils sont absents. */
    public record Totals(
            BigDecimal retentionAmountFcfa,
            BigDecimal marginAmountFcfa,
            BigDecimal weightKg,
            BigDecimal deliveredFcfa,
            int delegateCount) {}
}
