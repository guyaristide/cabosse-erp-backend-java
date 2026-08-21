package com.ntech.cabosse.permission.entity;

import com.ntech.cabosse.tenant.capability.TenantCapability;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Droit élémentaire sur une fonctionnalité (backlog ADM-01).
 *
 * <p>Le rôle {@code USER} ne dit rien de ce qu'une personne a le droit de
 * faire : un magasinier qui pèse du cacao et un comptable qui solde des
 * livraisons portaient jusqu'ici le même rôle et les mêmes accès. La
 * permission est l'unité qui manquait ; l'administrateur du tenant les
 * assemble en profils correspondant à son organisation.</p>
 *
 * <p>Chaque permission déclare les <strong>capacités qu'elle suppose</strong>.
 * Une coopérative sans négoce n'a pas de reçus d'achat producteur : les
 * permissions correspondantes n'existent pas pour elle, ni dans les profils
 * qu'elle compose, ni dans les droits de son administrateur. Le catalogue
 * des droits suit donc le périmètre réellement activé, au lieu d'offrir des
 * cases à cocher sans objet.</p>
 */
public enum Permission {

    // ─── Référentiels ───────────────────────────────────────────────
    REFERENTIAL_READ(Domain.REFERENTIAL, "Consulter les référentiels"),
    REFERENTIAL_WRITE(Domain.REFERENTIAL, "Créer et modifier les référentiels"),

    // ─── Achats de biens et services ────────────────────────────────
    PURCHASE_READ(Domain.PURCHASE, "Consulter les achats"),
    PURCHASE_WRITE(Domain.PURCHASE, "Saisir bons de commande et réceptions"),
    PURCHASE_APPROVE(Domain.PURCHASE, "Approuver les demandes d'achat"),
    EXPENSE_WRITE(Domain.PURCHASE, "Enregistrer les dépenses directes"),

    // ─── Collecte de matière première ───────────────────────────────
    COLLECTION_READ(Domain.COLLECTION, "Consulter la collecte",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_RECEIPT_WRITE(Domain.COLLECTION, "Enregistrer un reçu d'achat producteur",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_ADVANCE_WRITE(Domain.COLLECTION, "Consentir une avance à un délégué",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_PAYMENT_WRITE(Domain.COLLECTION, "Régler les livraisons",
            TenantCapability.HAS_COMMODITY_TRADE),

    // ─── Producteurs membres ────────────────────────────────────────
    MEMBER_READ(Domain.MEMBER, "Consulter les producteurs", TenantCapability.HAS_MEMBERS),
    MEMBER_WRITE(Domain.MEMBER, "Créer et modifier les producteurs", TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_REQUEST(Domain.MEMBER, "Demander un crédit ou une avance",
            TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_APPROVE(Domain.MEMBER, "Approuver un crédit ou une avance",
            TenantCapability.HAS_MEMBERS),
    // Au-dessus du seuil du tenant, l'approbation ordinaire ne suffit
    // plus : ce droit distinct matérialise l'échelon de gouvernance.
    MEMBER_CREDIT_APPROVE_GOVERNANCE(Domain.MEMBER, "Approuver un crédit soumis au conseil",
            TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_DISBURSE(Domain.MEMBER, "Décaisser un crédit approuvé",
            TenantCapability.HAS_MEMBERS),

    // ─── Amont agricole ─────────────────────────────────────────────
    PARCEL_READ(Domain.AGRICULTURE, "Consulter les parcelles", TenantCapability.HAS_PARCELS),
    PARCEL_WRITE(Domain.AGRICULTURE, "Créer et modifier les parcelles", TenantCapability.HAS_PARCELS),
    HARVEST_WRITE(Domain.AGRICULTURE, "Enregistrer les récoltes", TenantCapability.HAS_PARCELS),

    // ─── Transformation ─────────────────────────────────────────────
    PROCESSING_READ(Domain.PROCESSING, "Consulter la transformation"),
    PRODUCTION_WRITE(Domain.PROCESSING, "Lancer et clôturer les ordres de fabrication"),
    FERMENTATION_WRITE(Domain.PROCESSING, "Suivre la fermentation",
            TenantCapability.HAS_FERMENTATION),
    DRYING_WRITE(Domain.PROCESSING, "Suivre le séchage", TenantCapability.HAS_DRYING),

    // ─── Stocks ─────────────────────────────────────────────────────
    STOCK_READ(Domain.STOCK, "Consulter les stocks"),
    STOCK_MOVE(Domain.STOCK, "Enregistrer les mouvements de stock"),
    STOCK_INVENTORY(Domain.STOCK, "Conduire un inventaire physique"),

    // ─── Ventes ─────────────────────────────────────────────────────
    SALE_READ(Domain.SALE, "Consulter les ventes"),
    SALE_WRITE(Domain.SALE, "Saisir devis, ventes et livraisons"),
    SALE_PAYMENT(Domain.SALE, "Encaisser les règlements clients"),

    // ─── Comptabilité et trésorerie ─────────────────────────────────
    ACCOUNTING_READ(Domain.ACCOUNTING, "Consulter la comptabilité"),
    ACCOUNTING_WRITE(Domain.ACCOUNTING, "Saisir les opérations diverses"),
    ACCOUNTING_CLOSE(Domain.ACCOUNTING, "Clôturer périodes et exercices"),
    TREASURY_WRITE(Domain.ACCOUNTING, "Transporter des fonds et tenir la caisse"),

    // ─── Conformité ─────────────────────────────────────────────────
    EUDR_READ(Domain.COMPLIANCE, "Consulter la conformité",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    EUDR_WRITE(Domain.COMPLIANCE, "Constituer les dossiers de conformité",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    TRACEABILITY_READ(Domain.COMPLIANCE, "Consulter la traçabilité"),

    // ─── Pilotage ───────────────────────────────────────────────────
    REPORTING_READ(Domain.STEERING, "Consulter les rapports"),
    EXECUTIVE_READ(Domain.STEERING, "Consulter le tableau de bord de direction"),

    // ─── Administration du tenant ───────────────────────────────────
    SETTINGS_READ(Domain.ADMIN, "Consulter les paramètres"),
    SETTINGS_WRITE(Domain.ADMIN, "Modifier les paramètres"),
    USER_MANAGE(Domain.ADMIN, "Gérer les utilisateurs et les profils");

    /** Regroupement d'affichage, pour composer un profil sans se perdre. */
    public enum Domain {
        REFERENTIAL, PURCHASE, COLLECTION, MEMBER, AGRICULTURE, PROCESSING,
        STOCK, SALE, ACCOUNTING, COMPLIANCE, STEERING, ADMIN
    }

    private final Domain domain;
    private final String label;
    private final Set<TenantCapability> requires;

    Permission(Domain domain, String label, TenantCapability... requires) {
        this.domain = domain;
        this.label = label;
        this.requires = requires.length == 0
                ? Set.of() : new LinkedHashSet<>(Arrays.asList(requires));
    }

    public Domain domain() { return domain; }

    public String label() { return label; }

    /** Capacités sans lesquelles cette permission n'a pas d'objet. */
    public Set<TenantCapability> requires() { return requires; }

    /**
     * La permission a-t-elle un sens pour un tenant doté de ces capacités ?
     * Toutes les capacités déclarées doivent être actives : une permission
     * ne se donne pas à moitié.
     */
    public boolean availableFor(Set<TenantCapability> capabilities) {
        return capabilities.containsAll(requires);
    }

    /** Catalogue applicable à un tenant, dans l'ordre de déclaration. */
    public static List<Permission> availableIn(Set<TenantCapability> capabilities) {
        return Arrays.stream(values()).filter(p -> p.availableFor(capabilities)).toList();
    }

    public static Permission ofCode(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
