package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte bancaire ou caisse ouvert au nom du tenant. Tenant-scopé
 * (collection {@code bank_accounts}).
 *
 * <p>Sert deux usages :
 * <ol>
 *   <li>Référence côté paiements : le {@link com.ntech.cabosse.sale.entity.SalePayment}
 *       (et son équivalent RD) cite ce compte pour déterminer le compte
 *       SYSCOHADA débité/crédité par {@code AccountingService}.</li>
 *   <li>Affichage du bandeau "soldes" sur la page Comptabilité. Le solde
 *       est <strong>dérivé</strong> à la demande depuis le journal — non
 *       stocké ici pour éviter toute divergence avec les écritures.</li>
 * </ol>
 */
public class BankAccountEntity {

    @BsonId
    public UUID id;

    /** Nom de la banque ("SGBCI", "BICICI", "Caisse"). */
    public String bankName;

    /**
     * Numéro de compte interne au tenant. Affiché partiellement masqué côté
     * UI (4 derniers chiffres). Pour une caisse, peut être vide.
     */
    public String accountNumber;

    /**
     * Compte SYSCOHADA rattaché (521 par défaut pour banque, 571 pour
     * caisse, mais le tenant peut ouvrir un 521-3 pour distinguer ses
     * comptes). Doit exister dans {@link ChartOfAccountsEntity}.
     */
    public String syscohadaAccount;

    /** Libellé court ("Compte courant XOF", "Caisse boutique Méagui"). */
    public String label;

    /** Sous-libellé qualificatif ("encaissements GMS", "paiements terrain"). */
    public String sub;

    public BankAccountKind kind = BankAccountKind.BANQUE;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public BankAccountEntity() {}
}
