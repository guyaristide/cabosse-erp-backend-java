package com.ntech.cabosse.producerpayment.entity;

/**
 * À qui la coopérative verse le règlement.
 *
 * <p>La distinction n'est pas cosmétique : elle décide du compte que le
 * règlement solde. Une livraison apportée par un délégué est due au
 * délégué, jamais au producteur qu'il a déjà payé.</p>
 */
public enum ProducerPaymentBeneficiary {

    /** Producteur membre payé en direct. */
    MEMBER,

    /** Délégué collecteur, pour les livraisons qu'il a apportées. */
    DELEGATE
}
