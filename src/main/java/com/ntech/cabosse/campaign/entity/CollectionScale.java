package com.ntech.cabosse.campaign.entity;

import java.math.BigDecimal;

/**
 * Le barème de collecte de la campagne, décomposé au kilo.
 *
 * <p>Demandé par la coopérative le 13/09/2026. Le conseil de filière
 * publie chaque campagne ce qu'il accorde à la chaîne de collecte, et le
 * décompose : le transport, le ramassage, la rémunération de l'acheteur.
 * La coopérative veut confronter ce qu'elle réalise à ce que le barème
 * prévoit.</p>
 *
 * <p>Le total ne se saisit pas, il se somme : un total saisi à part
 * finirait par ne plus correspondre à ses composantes, et personne ne
 * saurait laquelle des deux vérités croire.</p>
 */
public class CollectionScale {

    /** Ce que le barème accorde au transport, au kilo. */
    public BigDecimal transportPerKg;

    /** Ce qu'il accorde au ramassage. */
    public BigDecimal gatheringPerKg;

    /** Ce qu'il accorde à la rémunération de l'acheteur. */
    public BigDecimal buyerRemunerationPerKg;

    /** La somme des composantes renseignées, ou null si aucune ne l'est. */
    public BigDecimal total() {
        BigDecimal sum = null;
        for (BigDecimal part : new BigDecimal[]{
                transportPerKg, gatheringPerKg, buyerRemunerationPerKg}) {
            if (part == null) continue;
            sum = sum == null ? part : sum.add(part);
        }
        return sum;
    }
}
