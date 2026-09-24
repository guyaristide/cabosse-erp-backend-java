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

    // Les droits par référentiel, ajoutés le 24/09/2026. Les deux droits
    // globaux ci-dessus restent : qui les détient détient toute cette
    // liste, l'expansion est faite dans PermissionResolver. Ils
    // permettent de confier un seul référentiel à quelqu'un sans lui
    // ouvrir les vingt et un autres.
    //
    // Aucune capacité n'y est déclarée, volontairement. Les deux droits
    // globaux n'en déclarent pas non plus : en attacher une ici
    // retirerait un accès qui existe aujourd'hui, et le découpage doit
    // rester sans effet pour qui détient déjà le droit global.
    REF_SUPPLIER_READ(Domain.REFERENTIAL, "m.per-ref-supplier-read"),
    REF_SUPPLIER_WRITE(Domain.REFERENTIAL, "m.per-ref-supplier-write"),
    REF_SUPPLIER_CATEGORY_READ(Domain.REFERENTIAL, "m.per-ref-supplier-category-read"),
    REF_SUPPLIER_CATEGORY_WRITE(Domain.REFERENTIAL, "m.per-ref-supplier-category-write"),
    REF_CUSTOMER_READ(Domain.REFERENTIAL, "m.per-ref-customer-read"),
    REF_CUSTOMER_WRITE(Domain.REFERENTIAL, "m.per-ref-customer-write"),
    REF_ARTICLE_READ(Domain.REFERENTIAL, "m.per-ref-article-read"),
    REF_ARTICLE_WRITE(Domain.REFERENTIAL, "m.per-ref-article-write"),
    REF_UNIT_READ(Domain.REFERENTIAL, "m.per-ref-unit-read"),
    REF_UNIT_WRITE(Domain.REFERENTIAL, "m.per-ref-unit-write"),
    REF_RECIPE_READ(Domain.REFERENTIAL, "m.per-ref-recipe-read"),
    REF_RECIPE_WRITE(Domain.REFERENTIAL, "m.per-ref-recipe-write"),
    REF_QUALITY_GRADE_READ(Domain.REFERENTIAL, "m.per-ref-quality-grade-read"),
    REF_QUALITY_GRADE_WRITE(Domain.REFERENTIAL, "m.per-ref-quality-grade-write"),
    REF_QUALITY_NORM_READ(Domain.REFERENTIAL, "m.per-ref-quality-norm-read"),
    REF_QUALITY_NORM_WRITE(Domain.REFERENTIAL, "m.per-ref-quality-norm-write"),
    REF_CERTIFICATION_READ(Domain.REFERENTIAL, "m.per-ref-certification-read"),
    REF_CERTIFICATION_WRITE(Domain.REFERENTIAL, "m.per-ref-certification-write"),
    REF_SITE_READ(Domain.REFERENTIAL, "m.per-ref-site-read"),
    REF_SITE_WRITE(Domain.REFERENTIAL, "m.per-ref-site-write"),
    REF_REGION_READ(Domain.REFERENTIAL, "m.per-ref-region-read"),
    REF_REGION_WRITE(Domain.REFERENTIAL, "m.per-ref-region-write"),
    REF_DEPARTMENT_READ(Domain.REFERENTIAL, "m.per-ref-department-read"),
    REF_DEPARTMENT_WRITE(Domain.REFERENTIAL, "m.per-ref-department-write"),
    REF_LOCALITY_READ(Domain.REFERENTIAL, "m.per-ref-locality-read"),
    REF_LOCALITY_WRITE(Domain.REFERENTIAL, "m.per-ref-locality-write"),
    REF_SECTION_READ(Domain.REFERENTIAL, "m.per-ref-section-read"),
    REF_SECTION_WRITE(Domain.REFERENTIAL, "m.per-ref-section-write"),
    REF_CROP_READ(Domain.REFERENTIAL, "m.per-ref-crop-read"),
    REF_CROP_WRITE(Domain.REFERENTIAL, "m.per-ref-crop-write"),
    REF_VARIETY_READ(Domain.REFERENTIAL, "m.per-ref-variety-read"),
    REF_VARIETY_WRITE(Domain.REFERENTIAL, "m.per-ref-variety-write"),
    REF_PAYMENT_TERM_READ(Domain.REFERENTIAL, "m.per-ref-payment-term-read"),
    REF_PAYMENT_TERM_WRITE(Domain.REFERENTIAL, "m.per-ref-payment-term-write"),
    REF_EXPENSE_TYPE_READ(Domain.REFERENTIAL, "m.per-ref-expense-type-read"),
    REF_EXPENSE_TYPE_WRITE(Domain.REFERENTIAL, "m.per-ref-expense-type-write"),
    REF_CAMPAIGN_READ(Domain.REFERENTIAL, "m.per-ref-campaign-read"),
    REF_CAMPAIGN_WRITE(Domain.REFERENTIAL, "m.per-ref-campaign-write"),
    REF_DELEGATE_STATUS_READ(Domain.REFERENTIAL, "m.per-ref-delegate-status-read"),
    REF_DELEGATE_STATUS_WRITE(Domain.REFERENTIAL, "m.per-ref-delegate-status-write"),
    REF_ID_DOCUMENT_TYPE_READ(Domain.REFERENTIAL, "m.per-ref-id-document-type-read"),
    REF_ID_DOCUMENT_TYPE_WRITE(Domain.REFERENTIAL, "m.per-ref-id-document-type-write"),
    REF_OPERATOR_READ(Domain.REFERENTIAL, "m.per-ref-operator-read"),
    REF_OPERATOR_WRITE(Domain.REFERENTIAL, "m.per-ref-operator-write"),

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
    // Au-dessus du seuil du tenant, l'approbation ordinaire ne suffit
    // plus : ce droit distinct matérialise l'échelon de gouvernance,
    // comme côté crédit producteur.
    COLLECTION_ADVANCE_APPROVE_GOVERNANCE(Domain.COLLECTION,
            "m.per-collection-advance-approve-governance",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_ADVANCE_DISBURSE(Domain.COLLECTION, "m.per-collection-advance-disburse",
            TenantCapability.HAS_COMMODITY_TRADE),
    COLLECTION_PAYMENT_WRITE(Domain.COLLECTION, "m.per-collection-payment-write",
            TenantCapability.HAS_COMMODITY_TRADE),
    // Régler le solde d'un délégué ou d'un producteur après
    // comptabilisation était un geste unique : la caissière enregistrait
    // le paiement et l'argent sortait, sans décision ni trace. Ce droit
    // est celui de trancher, distinct de celui de payer (demande de
    // l'expert-comptable, 12/09/2026).
    COLLECTION_SETTLEMENT_APPROVE(Domain.COLLECTION,
            "m.per-collection-settlement-approve",
            TenantCapability.HAS_COMMODITY_TRADE),
    // Au-dessus du second seuil, l'approbation ordinaire ne suffit plus,
    // comme pour les avances.
    COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE(Domain.COLLECTION,
            "m.per-collection-settlement-approve-governance",
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
    // Les à-nouveaux réécrivent le point de départ de l'exercice : soldes
    // d'ouverture, créances, caisses et banques. Un droit à part, que la
    // structure confie à qui elle veut, distinct de l'écriture courante.
    ACCOUNTING_OPENING_WRITE(Domain.ACCOUNTING, "m.per-accounting-opening-write"),
    TREASURY_WRITE(Domain.ACCOUNTING, "m.per-treasury-write"),
    // Le solde des comptes est une information de gouvernance : ce droit
    // ouvre tous les soldes, banque comprise. Sans lui, une personne ne
    // lit que le solde des caisses dont elle est désignée gestionnaire.
    // Trois portées de lecture des soldes : les caisses, les banques, ou
    // tout. La structure compose : une caissière en chef lit toutes les
    // caisses sans la banque, la gouvernance lit tout.
    TREASURY_CASH_BALANCE(Domain.ACCOUNTING, "m.per-treasury-cash-balance"),
    TREASURY_BANK_BALANCE(Domain.ACCOUNTING, "m.per-treasury-bank-balance"),
    TREASURY_BALANCE_ALL(Domain.ACCOUNTING, "m.per-treasury-balance-all"),
    /**
     * Voir le solde net (encaissements moins décaissements) des états de
     * trésorerie. Distinct des droits de solde par compte : un profil
     * peut suivre les flux sans lire les positions, et inversement.
     * Prévu pour garder d'autres indicateurs agrégés à venir.
     */
    TREASURY_NET_BALANCE(Domain.ACCOUNTING, "m.per-treasury-net-balance"),

    // ─── Conformité ─────────────────────────────────────────────────
    EUDR_READ(Domain.COMPLIANCE, "m.per-eudr-read",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    EUDR_WRITE(Domain.COMPLIANCE, "m.per-eudr-write",
            TenantCapability.HAS_EUDR_COMPLIANCE),
    TRACEABILITY_READ(Domain.COMPLIANCE, "m.per-traceability-read"),

    // ─── Pilotage ───────────────────────────────────────────────────
    REPORTING_READ(Domain.STEERING, "m.per-reporting-read"),
    EXECUTIVE_READ(Domain.STEERING, "m.per-executive-read"),
    /**
     * Lire le pilotage de campagne sans en voir les montants.
     *
     * <p>Un responsable de collecte a besoin des tonnages, des objectifs
     * et des écarts pour faire son travail, et n'a pas à connaître le
     * chiffre d'affaires, les marges ni la trésorerie. Ce droit ouvre la
     * vue campagne ; {@link #EXECUTIVE_READ} y ajoute l'argent (demandé
     * le 13/09/2026).</p>
     */
    CAMPAIGN_STEERING_READ(Domain.STEERING, "m.per-campaign-steering-read"),

    // ─── Administration du tenant ───────────────────────────────────
    SETTINGS_READ(Domain.ADMIN, "m.per-settings-read"),
    SETTINGS_WRITE(Domain.ADMIN, "m.per-settings-write"),
    /**
     * Déclarer les serveurs d'envoi de la structure. Distinct de
     * l'écriture des réglages : ces valeurs portent des identifiants de
     * connexion, et les confier revient à confier un moyen d'envoyer sous
     * le nom de la coopérative.
     */
    NOTIFICATION_PROVIDER_WRITE(Domain.ADMIN, "m.per-notification-provider-write"),
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

    /**
     * Les droits de lecture par référentiel, que {@code REFERENTIAL_READ}
     * ouvre en bloc.
     *
     * <p>Dérivés du nom plutôt que listés à la main : une liste parallèle
     * oublie le droit qu'on vient d'ajouter, et l'oubli ne se voit
     * qu'au moment où quelqu'un perd un accès.</p>
     */
    public static Set<Permission> referentialReads() {
        return byPrefixAndSuffix("REF_", "_READ");
    }

    /** Les droits d'écriture par référentiel, ouverts par {@code REFERENTIAL_WRITE}. */
    public static Set<Permission> referentialWrites() {
        return byPrefixAndSuffix("REF_", "_WRITE");
    }

    private static Set<Permission> byPrefixAndSuffix(String prefix, String suffix) {
        Set<Permission> out = new LinkedHashSet<>();
        for (Permission p : values()) {
            if (p.name().startsWith(prefix) && p.name().endsWith(suffix)) out.add(p);
        }
        return out;
    }

    public static Permission ofCode(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
