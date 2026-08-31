package com.ntech.cabosse.reception.entity;

/**
 * Mode de règlement d'une opération : l'instrument réellement employé,
 * et non la nature du compte qui le dénoue.
 *
 * <p>{@code CHEQUE} et {@code BANK_TRANSFER} sortent tous deux d'un compte
 * de banque, et sont pourtant distincts : ce ne sont pas les mêmes
 * instruments, ils ne coûtent pas la même chose, et surtout un chèque
 * s'encaisse au guichet. C'est ce qui le rend indispensable ici : les
 * bénéficiaires n'ont pas toujours de compte en banque, on ne peut donc
 * rien leur virer, mais on peut leur remettre un chèque.</p>
 *
 * <p>{@code PRODUCER_CARD} : carte de paiement remise au producteur
 * (achat de matière première).</p>
 */
public enum PaymentMethod {
    CASH, MOBILE_MONEY, CHEQUE, BANK_TRANSFER, PRODUCER_CARD, OTHER
}
