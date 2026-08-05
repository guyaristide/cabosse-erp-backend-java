package com.ntech.cabosse.membercredit.entity;

/**
 * Cycle de vie d'un crédit ou d'une avance à un producteur membre.
 *
 * <p>Le circuit reproduit celui de la coopérative : une demande est
 * appréciée, approuvée par l'échelon compétent, puis décaissée par la
 * comptabilité. Un engagement non approuvé ne se décaisse pas, et un
 * engagement non décaissé ne s'impute sur aucune livraison.</p>
 */
public enum MemberCreditStatus {

    /** Demande enregistrée, en attente de l'approbation requise. */
    PENDING_APPROVAL,

    /** Approuvée, en attente de décaissement par la comptabilité. */
    APPROVED,

    /** Refusée. Terminal. */
    REJECTED,

    /** Fonds remis au producteur. Les retenues peuvent commencer. */
    DISBURSED,

    /** Intégralement remboursée par retenues. Terminal. */
    SETTLED,

    /** Abandonnée avant décaissement, ou soldée par décision de gestion. */
    CANCELLED
}
