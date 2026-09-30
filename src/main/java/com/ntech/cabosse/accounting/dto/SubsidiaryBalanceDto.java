package com.ntech.cabosse.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * La balance auxiliaire : ce que doit chaque tiers, sous son collectif.
 *
 * <p>Le compte collectif donne le total dû par l'ensemble, les comptes
 * auxiliaires le détail par tiers. Un ERP ne mouvemente pas les deux à
 * la fois, sans quoi chaque montant compterait double : l'écriture va
 * sur le compte du tiers quand il en a un, sur le collectif sinon. Le
 * collectif est donc un regroupement, et son solde propre est ce qui
 * reste porté par les tiers à qui personne n'a ouvert de compte.</p>
 *
 * <p>C'est ce que dit {@code unallocated} : tant qu'il n'est pas nul, le
 * détail par tiers est incomplet, et l'état le montre plutôt que de
 * l'additionner en silence (demandé le 29/09/2026).</p>
 */
public record SubsidiaryBalanceDto(
        List<Group> groups,
        /** Comptes mouvementés qu'aucune fiche ne revendique. */
        List<Row> orphans
) {

    /** Un compte collectif et le détail des tiers qui s'y rattachent. */
    public record Group(
            String collectiveAccount,
            String collectiveLabel,
            List<Row> parties,
            /** Somme des soldes des tiers rattachés. */
            BigDecimal partiesBalance,
            /** Solde propre du collectif : les tiers sans compte à eux. */
            BigDecimal unallocated,
            /** Ce que doit le groupe entier, détail et non ventilé compris. */
            BigDecimal total
    ) {}

    /** Un tiers, son compte, et ce qu'il doit. */
    public record Row(
            String account,
            String partyName,
            /** « CUSTOMER » ou « SUPPLIER » : deux tiers peuvent se nommer pareil. */
            String partyType,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal balance
    ) {}
}
