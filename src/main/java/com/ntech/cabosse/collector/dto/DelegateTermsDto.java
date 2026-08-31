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
        /**
         * Le délégué traîne une dette et aucune mise en compte n'est
         * convenue avec lui.
         *
         * <p>C'était un refus : la demande d'avance était rejetée tant
         * qu'une retenue par kilo n'était pas portée sur sa fiche. C'est
         * devenu un avertissement. Refinancer un délégué endetté sans
         * contrepartie est une décision, et le logiciel qui la prenait à
         * la place de la gouvernance se substituait à elle.</p>
         */
        boolean retentionMissingOnPriorDebt,
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
        BigDecimal suggestedAdvanceFcfa,
        /**
         * Le volume qu'un montant demandé représente : montant ÷ prix
         * barème.
         *
         * <p>C'est le sens le plus courant sur le terrain : le délégué
         * demande une somme, et c'est le volume qu'il devra livrer qu'on
         * en déduit. L'autre sens, du volume vers le montant, sert quand
         * il annonce ce qu'il a déjà rassemblé.</p>
         */
        BigDecimal suggestedVolumeKg,
        /**
         * Ce qui reste à justifier sur la campagne en cours : avances
         * décaissées et non encore couvertes par des livraisons.
         *
         * <p>Un constat, jamais une garde. Financer un délégué qui traîne
         * un encours est une décision de la gouvernance, pas une règle que
         * le logiciel arbitre : il montre ce qu'il faut pour décider, et
         * s'arrête là.</p>
         */
        BigDecimal outstandingFcfa
) {}
