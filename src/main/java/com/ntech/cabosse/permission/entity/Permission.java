package com.ntech.cabosse.permission.entity;

import com.ntech.cabosse.shared.i18n.Messages;
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
    REFERENTIAL_READ(Domain.REFERENTIAL, "m.per-referential-read"),
    REFERENTIAL_WRITE(Domain.REFERENTIAL, "m.per-referential-write"),
    // Le barème d'une campagne est le prix payé au producteur : il ne se
    // modifie pas au même titre qu'un libellé de référentiel. Le droit est
    // distinct pour que la structure décide qui le détient, direction ou
    // conseil, sans que le logiciel en tranche à sa place.
    CAMPAIGN_PRICE_WRITE(Domain.REFERENTIAL, "m.per-campaign-price-write"),

    // ─── Achats de biens et services ────────────────────────────────
    PURCHASE_READ(Domain.PURCHASE, "m.per-purchase-read"),
    PURCHASE_WRITE(Domain.PURCHASE, "m.per-purchase-write"),
    PURCHASE_APPROVE(Domain.PURCHASE, "m.per-purchase-approve"),
    EXPENSE_WRITE(Domain.PURCHASE, "m.per-expense-write"),

    // ─── Collecte de matière première ───────────────────────────────
    COLLECTION_READ(Domain.COLLECTION, "m.per-collection-read",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_RECEIPT_WRITE(Domain.COLLECTION, "m.per-collection-receipt-write",
            TenantCapability.HAS_COMMODITY_TRADE),
    // Trois droits pour trois gestes, parce que trois gestes valent mieux
    // qu'un sur la plus grosse sortie de trésorerie d'une campagne. La
    // structure les attribue aux profils qu'elle veut : le logiciel ne
    // décide pas qui, dans une coopérative, approuve un financement.
    COLLECTION_ADVANCE_REQUEST(Domain.COLLECTION, "m.per-collection-advance-request",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_ADVANCE_APPROVE(Domain.COLLECTION, "m.per-collection-advance-approve",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_ADVANCE_DISBURSE(Domain.COLLECTION, "m.per-collection-advance-disburse",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_PAYMENT_WRITE(Domain.COLLECTION, "m.per-collection-payment-write",
            TenantCapability.HAS_COMMODITY_TRADE),

    // ─── Producteurs membres ────────────────────────────────────────
    MEMBER_READ(Domain.MEMBER, "m.per-member-read", TenantCapability.HAS_MEMBERS),
    MEMBER_WRITE(Domain.MEMBER, "m.per-member-write", TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_REQUEST(Domain.MEMBER, "m.per-member-credit-request",
            TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_APPROVE(Domain.MEMBER, "m.per-member-credit-approve",
            TenantCapability.HAS_MEMBERS),
    // Au-dessus du seuil du tenant, l'approbation ordinaire ne suffit
    // plus : ce droit distinct matérialise l'échelon de gouvernance.
    MEMBER_CREDIT_APPROVE_GOVERNANCE(Domain.MEMBER, "m.per-member-credit-approve-governance",
            TenantCapability.HAS_MEMBERS),
    MEMBER_CREDIT_DISBURSE(Domain.MEMBER, "m.per-member-credit-disburse",
            TenantCapability.HAS_MEMBERS),

    // ─── Amont agricole ─────────────────────────────────────────────
    PARCEL_READ(Domain.AGRICULTURE, "m.per-parcel-read", TenantCapability.HAS_PARCELS),
    PARCEL_WRITE(Domain.AGRICULTURE, "m.per-parcel-write", TenantCapability.HAS_PARCELS),
    HARVEST_WRITE(Domain.AGRICULTURE, "m.per-harvest-write", TenantCapability.HAS_PARCELS),

    // ─── Transformation ─────────────────────────────────────────────
    PROCESSING_READ(Domain.PROCESSING, "m.per-processing-read"),
    PRODUCTION_WRITE(Domain.PROCESSING, "m.per-production-write"),
    FERMENTATION_WRITE(Domain.PROCESSING, "m.per-fermentation-write",
            TenantCapability.HAS_FERMENTATION),
    DRYING_WRITE(Domain.PROCESSING, "m.per-drying-write", TenantCapability.HAS_DRYING),

    // ─── Stocks ─────────────────────────────────────────────────────
    STOCK_READ(Domain.STOCK, "m.per-stock-read"),
    STOCK_MOVE(Domain.STOCK, "m.per-stock-move"),
    STOCK_INVENTORY(Domain.STOCK, "m.per-stock-inventory"),

    // ─── Ventes ─────────────────────────────────────────────────────
    SALE_READ(Domain.SALE, "m.per-sale-read"),
    SALE_WRITE(Domain.SALE, "m.per-sale-write"),
    SALE_PAYMENT(Domain.SALE, "m.per-sale-payment"),

    // ─── Comptabilité et trésorerie ─────────────────────────────────
    ACCOUNTING_READ(Domain.ACCOUNTING, "m.per-accounting-read"),
    ACCOUNTING_WRITE(Domain.ACCOUNTING, "m.per-accounting-write"),
    ACCOUNTING_CLOSE(Domain.ACCOUNTING, "m.per-accounting-close"),
    TREASURY_WRITE(Domain.ACCOUNTING, "m.per-treasury-write"),

    // ─── Conformité ─────────────────────────────────────────────────
    EUDR_READ(Domain.COMPLIANCE, "m.per-eudr-read",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    EUDR_WRITE(Domain.COMPLIANCE, "m.per-eudr-write",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    TRACEABILITY_READ(Domain.COMPLIANCE, "m.per-traceability-read"),

    // ─── Pilotage ───────────────────────────────────────────────────
    REPORTING_READ(Domain.STEERING, "m.per-reporting-read"),
    EXECUTIVE_READ(Domain.STEERING, "m.per-executive-read"),

    // ─── Administration du tenant ───────────────────────────────────
    SETTINGS_READ(Domain.ADMIN, "m.per-settings-read"),
    SETTINGS_WRITE(Domain.ADMIN, "m.per-settings-write"),
    USER_MANAGE(Domain.ADMIN, "m.per-user-manage"),
    /*
     * Le journal d'audit n'était gardé que par le rôle d'administrateur.
     * C'était trop rigide dans un sens et trop large dans l'autre : la
     * structure ne pouvait pas l'ouvrir à un contrôleur ou à son expert
     * comptable, et tout administrateur y accédait sans que ce soit un
     * choix. Un droit dédié rend la décision à la structure.
     */
    AUDIT_READ(Domain.ADMIN, "m.per-audit-read");

    /** Regroupement d'affichage, pour composer un profil sans se perdre. */
    public enum Domain {
        REFERENTIAL, PURCHASE, COLLECTION, MEMBER, AGRICULTURE, PROCESSING,
        STOCK, SALE, ACCOUNTING, COMPLIANCE, STEERING, ADMIN
    }

    private final Domain domain;
    private final String messageKey;
    private final Set<TenantCapability> requires;

    Permission(Domain domain, String messageKey, TenantCapability... requires) {
        this.domain = domain;
        this.messageKey = messageKey;
        this.requires = requires.length == 0
                ? Set.of() : new LinkedHashSet<>(Arrays.asList(requires));
    }

    public Domain domain() { return domain; }

    /** Clé de catalogue portant l'intitulé du droit. */
    public String messageKey() { return messageKey; }

    /**
     * Intitulé du droit dans la langue de la requête en cours.
     *
     * <p>Il était écrit en dur ici. L'administrateur d'une structure
     * anglophone cochait donc des cases françaises pour composer ses
     * profils, et le refus d'accès qui nomme le droit manquant sortait à
     * moitié dans chaque langue : la phrase venait du catalogue, le nom du
     * droit non.</p>
     */
    public String label() { return Messages.msg(messageKey); }

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
