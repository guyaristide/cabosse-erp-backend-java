package com.ntech.cabosse.accounting.entity;

import java.math.BigDecimal;

/**
 * Ligne d'une pièce comptable. Stockée dans le tableau
 * {@link JournalPieceEntity#entries} (embed, pas de collection séparée :
 * une entry n'a aucun sens en dehors de sa pièce).
 *
 * <p>Invariant : exactement un des deux champs {@code debitFcfa} /
 * {@code creditFcfa} est renseigné, jamais les deux, jamais aucun.
 * Vérifié par {@code AccountingService.postPiece}.</p>
 */
public class JournalEntry {

    /** Compte SYSCOHADA destinataire — référence {@link ChartOfAccountsEntity#number}. */
    public String syscohadaAccount;

    /** Libellé long de la ligne (snapshot fournisseur/client, désignation article). */
    public String libelle;

    /** Montant débité ou {@code null}. */
    public BigDecimal debitFcfa;

    /** Montant crédité ou {@code null}. */
    public BigDecimal creditFcfa;

    public JournalEntry() {}

    public static JournalEntry debit(String account, String libelle, BigDecimal amount) {
        JournalEntry e = new JournalEntry();
        e.syscohadaAccount = account;
        e.libelle = libelle;
        e.debitFcfa = amount;
        return e;
    }

    public static JournalEntry credit(String account, String libelle, BigDecimal amount) {
        JournalEntry e = new JournalEntry();
        e.syscohadaAccount = account;
        e.libelle = libelle;
        e.creditFcfa = amount;
        return e;
    }
}
