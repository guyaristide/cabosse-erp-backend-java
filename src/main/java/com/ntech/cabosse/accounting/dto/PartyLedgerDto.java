package com.ntech.cabosse.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Le grand livre d'un tiers, avec ses lettres de rapprochement.
 *
 * <p>Lettrer, c'est dire quelle facture a été payée par quel règlement.
 * Les lignes appariées portent la même lettre et ne se regardent plus ;
 * ce qui reste sans lettre est ce qui reste dû, et c'est le seul chiffre
 * qu'on relance (demandé le 29/09/2026).</p>
 *
 * <p>Les lettres se déduisent des montants, elles ne sont pas conservées
 * en base : un rapprochement figé vieillirait mal, puisqu'une écriture
 * ajoutée ou contre-passée change ce qui s'apparie. Recalculer à chaque
 * lecture rend toujours l'état du jour.</p>
 */
public record PartyLedgerDto(
        String account,
        String partyName,
        List<Row> rows,
        /** Ce qui reste dû après rapprochement : la somme des lignes sans lettre. */
        BigDecimal openBalance
) {

    public record Row(
            LocalDate date,
            String pieceRef,
            String sourceRef,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal runningBalance,
            /** La lettre du rapprochement, ou rien si la ligne reste ouverte. */
            String letter
    ) {}
}
