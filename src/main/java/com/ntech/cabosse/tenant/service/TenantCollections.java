package com.ntech.cabosse.tenant.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * À quelle famille appartient chaque collection d'une base de structure.
 *
 * <p>Cette carte décide ce qu'une remise à plat partielle emporte. Une
 * collection qui y manquerait serait effacée en silence alors que
 * l'utilisateur a demandé à la garder : c'est pourquoi un test refuse
 * qu'une collection existe sans être classée ici, plutôt que de laisser un
 * défaut se deviner.</p>
 *
 * <p>Les collections techniques n'y figurent pas : la base est détruite
 * puis reconstruite par les migrations, qui les recréent. Les classer
 * n'aurait pas de sens, les conserver en aurait encore moins.</p>
 */
public final class TenantCollections {

    /** Les profils de droits, conservés quoi qu'il arrive. */
    public static final String ROLES = "tenant_roles";

    /**
     * Reconstruites par les migrations, jamais classées ni conservées.
     *
     * <p>Le journal des migrations en fait partie : le garder ferait
     * croire à Mongock que tout est déjà joué sur une base vide.</p>
     */
    public static final Set<String> TECHNICAL = Set.of(
            "_provisioning_marker", "mongockChangeLog", "mongockLock");

    private static final List<String> SETTINGS = List.of(
            "accounting_periods",
            "campaign_targets",
            "campaigns",
            "fiscal_years",
            "notification_rules",
            // Les seuils d'acceptation sont une règle de la structure, non
            // une nomenclature : la liste des grades, elle, est ailleurs.
            "quality_norms",
            "sites");

    private static final List<String> NOMENCLATURES = List.of(
            "allocation_keys",
            "articles",
            "bank_accounts",
            "certifications",
            "chart_of_accounts",
            "cost_centers",
            "crops",
            "delegate_statuses",
            "departments",
            "expense_types",
            "id_document_types",
            "localities",
            "operators",
            "payment_terms",
            "programs",
            "quality_grades",
            "recipes",
            "regions",
            "sections",
            "supplier_categories",
            "units",
            "varieties");

    private static final List<String> PARTIES = List.of(
            "customers",
            "members",
            "parcels",
            "suppliers",
            // La position tenue sur un délégué qualifie le tiers : la
            // perdre en gardant sa fiche ferait revenir en « ordinaire »
            // un délégué que la structure avait écarté.
            "delegate_status_positions");

    private static final List<String> OPERATIONS = List.of(
            "accounting_quarantine",
            "advance_refunds",
            "bank_statement_lines",
            "bank_statements",
            "bean_quality_checks",
            "cash_counts",
            "cloud_files",
            "collector_advances",
            "commodity_sales",
            "counters",
            "deforestation_alerts",
            "delegate_opening_balances",
            "direct_expenses",
            "direct_receipts",
            "dispatch_notes",
            "drying_batches",
            "due_diligence_statements",
            "eudr_dossiers",
            "fermentation_batches",
            "harvests",
            "idempotency_keys",
            "import_runs",
            "intake_notes",
            "inventory_sessions",
            "journal_pieces",
            "manufacturing_orders",
            "member_credits",
            "notification_deliveries",
            "od_drafts",
            "outflow_notes",
            "producer_payments",
            "producer_purchases",
            "purchase_orders",
            "purchase_requests",
            "sales",
            "sales_contracts",
            "settlement_requests",
            "stock_items",
            "stock_movements",
            "treasury_transfers",
            "tva_declarations");

    private static final Map<String, TenantDataCategory> BY_COLLECTION = index();

    private TenantCollections() {}

    private static Map<String, TenantDataCategory> index() {
        Map<String, TenantDataCategory> map = new LinkedHashMap<>();
        SETTINGS.forEach(c -> map.put(c, TenantDataCategory.SETTINGS));
        NOMENCLATURES.forEach(c -> map.put(c, TenantDataCategory.NOMENCLATURES));
        PARTIES.forEach(c -> map.put(c, TenantDataCategory.PARTIES));
        OPERATIONS.forEach(c -> map.put(c, TenantDataCategory.OPERATIONS));
        return Map.copyOf(map);
    }

    /** La famille d'une collection, ou {@code null} si elle n'est pas classée. */
    public static TenantDataCategory categoryOf(String collection) {
        return BY_COLLECTION.get(collection);
    }

    /** Toutes les collections classées. */
    public static Set<String> classified() {
        return BY_COLLECTION.keySet();
    }

    /**
     * Les collections des familles demandées, profils de droits compris.
     *
     * <p>Les profils s'ajoutent toujours : leurs identifiants sont
     * référencés par les comptes utilisateurs du plan de contrôle, et les
     * régénérer priverait chaque collaborateur de ses accès sans le
     * dire.</p>
     */
    public static Set<String> toKeep(Set<TenantDataCategory> categories) {
        Set<String> kept = new LinkedHashSet<>();
        kept.add(ROLES);
        BY_COLLECTION.forEach((collection, category) -> {
            // Les opérations ne se conservent pas : les effacer est la
            // raison même d'une remise à plat.
            if (category != TenantDataCategory.OPERATIONS && categories.contains(category)) {
                kept.add(collection);
            }
        });
        return kept;
    }
}
