package com.ntech.cabosse.accounting.export;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tuples plats consommés par les exports comptables (balance, grand-livre,
 * journal). Volontairement séparés des entités pour ne pas exposer
 * d'identifiants UUID ou de champs techniques dans les fichiers livrés à
 * l'expert-comptable.
 */
public final class AccountingExportRows {

    private AccountingExportRows() {}

    /** Une ligne de la balance générale — un compte SYSCOHADA + ses totaux. */
    public record BalanceRow(
            String accountNumber,
            String accountLabel,
            String family,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal balance
    ) {}

    /** Une ligne du grand-livre — une écriture sur un compte donné, avec solde progressif. */
    public record GrandLivreRow(
            LocalDate date,
            String pieceRef,
            String sourceRef,
            String libelle,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal runningBalance
    ) {}

    /** Une ligne du journal complet — entry à plat avec contexte pièce. */
    /** Ligne d'état financier (bilan ou compte de résultat) : masse, rubrique, montant. */
    public record StatementRow(
            String section,
            String rubrique,
            java.math.BigDecimal montant
    ) {}

    public record JournalRow(
            LocalDate date,
            String pieceRef,
            String sourceType,
            String sourceRef,
            String libellePiece,
            String accountNumber,
            String libelleLigne,
            BigDecimal debit,
            BigDecimal credit
    ) {}
}
