package com.ntech.cabosse.accounting.entity;

/**
 * Nature d'un compte de trésorerie ouvert au nom du tenant.
 *
 * <ul>
 *   <li>{@link #BANQUE} — compte courant, généralement adossé au plan
 *       SYSCOHADA 521. Reçoit virements clients et règle les fournisseurs.</li>
 *   <li>{@link #CAISSE} — caisse espèces (boutique, terrain), 530 SYSCOHADA.
 *       Encaissements cash, paiements producteurs en RD.</li>
 * </ul>
 */
public enum BankAccountKind {
    BANQUE,
    CAISSE
}
