package com.ntech.cabosse.producerpurchase.entity;

import java.math.BigDecimal;

/**
 * Une pesée du bordereau de réception (épic magasin, CE-183).
 *
 * <p>Le carnet de la coopérative pèse une livraison en plusieurs passages
 * sur la bascule : chaque ligne porte le poids brut, une décote et le
 * poids net, et le bordereau totalise. Le net seul se paie et entre en
 * stock ; le brut et la décote restent la trace de la pesée, celle que le
 * fournisseur vise.</p>
 *
 * <p>La décote est saisie librement en kilos : sa signification exacte
 * sur le carnet (tare des sacs, réfaction d'humidité) est en cours de
 * clarification avec l'expert (DEC-34), et cette forme reste juste
 * quelle que soit la réponse.</p>
 */
public class PurchaseWeighing {

    public BigDecimal grossKg;

    /**
     * Sacs de cette pesée, la colonne « MS » du carnet (DEC-34, tranchée
     * le 04/09/2026 par l'expert). Le net proposé vaut brut moins sacs,
     * un kilo de tare par sac.
     */
    public Integer bagsCount;

    /**
     * Kilos retirés du brut. Sans saisie, vaut le nombre de sacs (un kilo
     * de tare par sac, la règle du carnet) ; saisie, elle fait foi.
     */
    public BigDecimal deductionKg;

    public BigDecimal netKg;

    public PurchaseWeighing() {}
}
