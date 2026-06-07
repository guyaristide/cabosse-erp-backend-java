package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte du plan comptable SYSCOHADA actif pour le tenant. Tenant-scopé
 * (collection {@code chart_of_accounts}).
 *
 * <p>Le plan est seedé au premier démarrage via la migration M011 avec
 * les ~12 comptes utilisés par le moteur de comptabilisation automatique
 * (cf. {@link SyscohadaAccounts}). Le tenant peut en ajouter d'autres au
 * fil de l'eau (sous-comptes 601200 par fournisseur stratégique, etc.)
 * mais ne peut pas supprimer un compte référencé par une écriture.</p>
 *
 * <p>Identité métier : {@link #number} — index unique en BD.</p>
 */
public class ChartOfAccountsEntity {

    @BsonId
    public UUID id;

    /** Numéro SYSCOHADA (3 à 8 chiffres). Ex. "401", "601", "4457". */
    public String number;

    /** Libellé court affiché en liste ("Fournisseurs", "Achats matières"). */
    public String label;

    public AccountFamily family;

    /**
     * Compte actif. Désactiver plutôt que supprimer si un compte n'est
     * plus utilisé — l'historique des écritures doit pouvoir être consulté.
     */
    public boolean active = true;

    /** {@code true} si seedé par M011 — protège la suppression côté service. */
    public boolean system = false;

    public Instant createdAt;
    public Instant updatedAt;

    public ChartOfAccountsEntity() {}
}
