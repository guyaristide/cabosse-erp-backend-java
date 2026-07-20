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

    /**
     * Centre de coût analytique (code du référentiel, backlog CPT-09).
     * {@code null} hors imputation. Ne se pose que sur une ligne de
     * charge (compte de classe 6) — contrôlé à la comptabilisation.
     */
    public String costCenter;

    /**
     * Programme budgétaire (code du référentiel, backlog CPT-10).
     * {@code null} hors imputation. Se pose sur une ligne de charge
     * (classe 6) ou de produit (classe 7) — contrôlé à la comptabilisation.
     */
    public String program;

    /** Projet du programme (optionnel), {@code null} si non précisé. */
    public String project;

    public JournalEntry() {}

    /** Pose le centre de coût et rend l'instance (chaînage à la construction). */
    public JournalEntry costCenter(String code) {
        this.costCenter = (code == null || code.isBlank()) ? null : code.trim();
        return this;
    }

    /** Pose le programme (et éventuellement le projet) et rend l'instance. */
    public JournalEntry program(String programCode, String projectCode) {
        this.program = (programCode == null || programCode.isBlank()) ? null : programCode.trim();
        this.project = (projectCode == null || projectCode.isBlank()) ? null : projectCode.trim();
        return this;
    }

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
