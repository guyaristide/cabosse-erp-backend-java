package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fiche technique d'un délégué pour une campagne : ce qu'il a été convenu
 * avec lui, et ce qu'il doit encore.
 *
 * <p>Elle répond à la question qu'on se pose avant de le financer : que
 * traîne-t-il de la campagne d'avant, à quel prix lui achète-t-on, et
 * combien lui verse-t-on pour le volume qu'il s'engage à livrer.</p>
 */
@Schema(description = "Fiche technique d'un délégué collecteur")
public record DelegateTermsDto(
        UUID delegateSupplierId,
        String delegateCode,
        String delegateName,
        UUID campaignId,
        String campaignLabel,
        /** Le délégué traîne-t-il un solde d'une campagne antérieure ? */
        boolean hasPriorDebt,
        /** Montant de cette dette, positif quand il doit à la coopérative. */
        BigDecimal priorDebtFcfa,
        /** Mise en compte convenue, en FCFA par kilo livré. */
        BigDecimal retentionPerKgFcfa,
        /** Marge de fonctionnement, en FCFA par kilo livré. */
        BigDecimal marginPerKgFcfa,
        /** Prix bord champ de la campagne, en FCFA par kilo. */
        BigDecimal basePricePerKgFcfa,
        /**
         * Prix barème délégué = prix bord champ + marge de fonctionnement.
         *
         * <p>Null quand la marge n'est pas exprimée au kilo : un
         * pourcentage ne s'ajoute pas à un prix unitaire, et afficher une
         * somme fausse serait pire que de ne rien afficher.</p>
         */
        BigDecimal scalePricePerKgFcfa,
        /**
         * Ce qu'il faudrait avancer pour un volume donné, quand l'appelant
         * en propose un : prix barème × volume.
         */
        BigDecimal suggestedAdvanceFcfa
) {}
