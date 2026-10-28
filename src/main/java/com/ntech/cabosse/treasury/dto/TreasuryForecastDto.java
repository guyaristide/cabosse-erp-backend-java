package com.ntech.cabosse.treasury.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Le solde de trésorerie à date, réel puis théorique (demandé le
 * 28/10/2026) : « savoir si on pourra faire face aux engagements ».
 *
 * <p>Solde net théorique = solde réel (banques et caisses) + à encaisser
 * − à payer. Un solde que le lecteur n'a pas le droit de voir est absent,
 * jamais à zéro : le théorique qui en dépendrait est absent aussi, et
 * {@code hiddenAccounts} dit combien de comptes manquent.</p>
 */
@Schema(description = "Solde de trésorerie réel et théorique à date")
public record TreasuryForecastDto(
        LocalDate at,
        /** Somme des soldes banques et caisses ; nul si un compte au moins est réservé au lecteur. */
        BigDecimal realBalance,
        /** Comptes actifs dont le solde est réservé à d'autres. */
        int hiddenAccounts,
        /** Reste à encaisser sur les ventes et ventes en gros. */
        BigDecimal toCollect,
        /** Reste à payer : avances, crédits, livraisons, réceptions, dépenses, abonnements. */
        BigDecimal toPay,
        /** realBalance + toCollect − toPay ; nul dès que realBalance l'est. */
        BigDecimal theoreticalBalance
) {}
