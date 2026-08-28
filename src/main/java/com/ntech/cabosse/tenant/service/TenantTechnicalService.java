package com.ntech.cabosse.tenant.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.tenant.dto.BackupSnapshotDto;
import com.ntech.cabosse.tenant.dto.CollectionStatDto;
import com.ntech.cabosse.tenant.dto.MigrationEntryDto;
import com.ntech.cabosse.tenant.dto.TenantTechnicalStatusDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Collecte le statut technique d'un tenant pour le back-office plateforme.
 *
 * <p>Aucune mutation ici — uniquement des reads contre la base tenant
 * ({@code db.stats}, {@code collStats}, {@code mongockChangeLog}) et le
 * control plane ({@code tenant_backups}). Les opérations sont indépendantes
 * et tolérantes : si une collection est introuvable on l'ignore, on ne
 * propage pas l'erreur — le diagnostic doit toujours renvoyer un payload
 * exploitable même si la base est dans un état dégradé.</p>
 *
 * <p>Les opérations d'écriture (relancer migrations, déclencher backup)
 * sont dans des services séparés (Phase D ultérieure).</p>
 */
@ApplicationScoped
public class TenantTechnicalService {

    /**
     * Catalogue statique des migrations attendues — reflète exactement les
     * {@code @ChangeUnit} de {@code com.ntech.cabosse.migrations} (mêmes
     * {@code id}/{@code order}/{@code author}). Le {@code changeId} doit
     * correspondre au {@code id} de l'annotation, car c'est la clé sous
     * laquelle Mongock écrit dans {@code mongockChangeLog} ; toute
     * divergence ferait apparaître la migration comme « pending » à tort.
     *
     * <p><strong>À tenir à jour à chaque ajout de {@code @ChangeUnit}.</strong>
     * Automatisation possible plus tard via un scan Jandex / IndexView ;
     * en attendant, un test de cohérence peut comparer ce catalogue au
     * package des migrations pour bloquer toute dérive.</p>
     */
    private record MigrationDescriptor(String changeId, String order, String author) {}

    private static final List<MigrationDescriptor> CATALOG = List.of(
            new MigrationDescriptor("bootstrap_tenant_schema", "001", "neiba"),
            new MigrationDescriptor("create_sites_collection", "002", "neiba"),
            new MigrationDescriptor("create_referentiel_collections", "003", "neiba"),
            new MigrationDescriptor("backfill_article_stockable", "004", "neiba"),
            new MigrationDescriptor("create_cloud_files_collection", "005", "neiba"),
            new MigrationDescriptor("create_purchase_orders_collection", "006", "neiba"),
            new MigrationDescriptor("create_direct_receipts_collection", "007", "neiba"),
            new MigrationDescriptor("create_stocks_collections", "008", "neiba"),
            new MigrationDescriptor("create_production_collection", "009", "neiba"),
            new MigrationDescriptor("create_sales_collection", "010", "neiba"),
            new MigrationDescriptor("create_accounting_collections", "011", "neiba"),
            new MigrationDescriptor("create_tva_declarations_collection", "012", "neiba"),
            new MigrationDescriptor("create_bank_reconciliation_collections", "013", "neiba"),
            new MigrationDescriptor("create_members_collection", "014", "neiba"),
            new MigrationDescriptor("create_parcels_collection", "015", "neiba"),
            new MigrationDescriptor("create_eudr_dossiers_collection", "016", "neiba"),
            new MigrationDescriptor("create_deforestation_alerts_collection", "017", "neiba"),
            new MigrationDescriptor("create_due_diligence_statements_collection", "018", "neiba"),
            new MigrationDescriptor("create_harvests_collection", "019", "neiba"),
            new MigrationDescriptor("create_fermentation_batches_collection", "020", "neiba"),
            new MigrationDescriptor("create_drying_batches_collection", "021", "neiba"),
            new MigrationDescriptor("create_bean_quality_checks_collection", "022", "neiba"),
            new MigrationDescriptor("create_campaigns_collection", "023", "neiba"),
            new MigrationDescriptor("normalize_stock_item_cmup_scale", "024", "neiba"),
            new MigrationDescriptor("create_units_collection", "025", "neiba"),
            new MigrationDescriptor("create_localities_collection", "026", "neiba"),
            new MigrationDescriptor("create_varieties_collection", "027", "neiba"),
            new MigrationDescriptor("create_operators_collection", "028", "neiba"),
            new MigrationDescriptor("create_accounting_periods_collection", "029", "neiba"),
            new MigrationDescriptor("create_inventory_sessions_collection", "030", "neiba"),
            new MigrationDescriptor("create_od_drafts_collection", "031", "neiba"),
            new MigrationDescriptor("align_chart_accounts_v7", "032", "neiba"),
            new MigrationDescriptor("add_capital_and_vat_accounts", "033", "neiba"),
            new MigrationDescriptor("seed_extended_chart_v7", "034", "neiba"),
            new MigrationDescriptor("create_fiscal_years_collection", "035", "neiba"),
            new MigrationDescriptor("create_cost_centers_collection", "036", "neiba"),
            new MigrationDescriptor("create_programs_collection", "037", "neiba"),
            new MigrationDescriptor("create_purchase_requests_collection", "038", "neiba"),
            new MigrationDescriptor("create_collector_collections", "039", "neiba"),
            new MigrationDescriptor("normalize_six_digit_accounts", "040", "neiba"),
            new MigrationDescriptor("create_direct_expenses_collection", "041", "neiba"),
            new MigrationDescriptor("create_allocation_keys_collection", "042", "neiba"),
            new MigrationDescriptor("create_referential_geo_collections", "043", "neiba"),
            new MigrationDescriptor("backfill_member_name", "044", "neiba"),
            new MigrationDescriptor("create_producer_purchases_collection", "045", "neiba"),
            new MigrationDescriptor("create_cacao_trade_collections", "046", "neiba"),
            new MigrationDescriptor("backfill_article_roles", "047", "neiba"),
            new MigrationDescriptor("producer_enrolment", "048", "neiba"),
            new MigrationDescriptor("link_campaign_on_harvests_and_advances", "049", "neiba"),
            new MigrationDescriptor("derive_campaign_year", "050", "neiba"),
            new MigrationDescriptor("delegate_current_account", "051", "neiba"),
            new MigrationDescriptor("producer_cards_as_documents", "052", "neiba"),
            new MigrationDescriptor("align_syscohada_merchandise_accounts", "053", "neiba"),
            new MigrationDescriptor("create_member_credits_collection", "054", "neiba"),
            new MigrationDescriptor("create_treasury_collections", "055", "neiba"),
            new MigrationDescriptor("create_producer_payments", "056", "neiba"),
            new MigrationDescriptor("separate_delegate_payable", "057", "neiba"),
            new MigrationDescriptor("create_supplier_categories", "058", "neiba"),
            new MigrationDescriptor("normalize_decimal_amounts", "059", "neiba"),
            new MigrationDescriptor("repair_producer_ref_keys_index", "060", "neiba"),
            new MigrationDescriptor("tenant_roles_and_default_profile", "061", "neiba"),
            new MigrationDescriptor("unique_official_receipt", "062", "neiba"),
            new MigrationDescriptor("backfill_replaces_cmup_flag", "063", "neiba"),
            new MigrationDescriptor("create_notification_deliveries", "064", "neiba"),
            new MigrationDescriptor("create_idempotency_keys", "065", "neiba"),
            new MigrationDescriptor("create_accounting_quarantine", "066", "neiba"),
            new MigrationDescriptor("link_campaign_on_operations", "067", "neiba"),
            new MigrationDescriptor("producer_purchase_status", "068", "neiba"),
            new MigrationDescriptor("delegate_covers_localities", "069", "neiba")
    );

    /** Fréquence de backup par plan tarifaire (cf. plans.json catalogue). */
    private static final Map<String, String> BACKUP_FREQUENCY_BY_PLAN = Map.of(
            "starter",    "weekly",
            "pro",        "daily",
            "scale",      "daily",
            "enterprise", "hourly"
    );

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject com.ntech.cabosse.plan.service.PlanLimitService planLimits;

    public TenantTechnicalStatusDto getStatus(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }

        MongoDatabase tenantDb = mongoClient.getDatabase(tenant.databaseName);

        // ─── dbStats ───
        long databaseSizeBytes = 0;
        try {
            Document dbStats = tenantDb.runCommand(new Document("dbStats", 1));
            // dataSize : taille des documents, storageSize : taille disque (avec WT compression).
            // On expose dataSize qui est plus interprétable côté UI.
            Number dataSize = (Number) dbStats.get("dataSize");
            if (dataSize != null) databaseSizeBytes = dataSize.longValue();
        } catch (RuntimeException ignored) {
            // Base inaccessible — on reste à 0, l'UI affichera "—".
        }

        // ─── Collections ───
        List<CollectionStatDto> collections = collectCollectionStats(tenantDb);

        // ─── Migrations Mongock ───
        List<MigrationEntryDto> migrations = collectMigrationHistory(tenantDb);
        String mongockVersion = computeMongockVersion(migrations);
        String migrationsHealth = computeMigrationsHealth(migrations);

        // ─── Consommation face au plan ───
        com.ntech.cabosse.plan.service.PlanLimitService.Usage usage = planLimits.usageOf(tenant);
        long membersCount = mongoClient.getDatabase(tenant.databaseName)
                .getCollection("members").countDocuments();

        // ─── Backups ───
        List<BackupSnapshotDto> recentBackups = collectRecentBackups(tenantId);
        String backupFrequency = BACKUP_FREQUENCY_BY_PLAN.getOrDefault(tenant.planCode, "weekly");

        return new TenantTechnicalStatusDto(
                tenant.id,
                tenant.databaseName,
                databaseSizeBytes,
                collections.size(),
                mongockVersion,
                migrationsHealth,
                Instant.now(),
                collections,
                migrations,
                backupFrequency,
                recentBackups,
                usage.activeUsers(),
                usage.maxUsers(),
                membersCount,
                usage.maxMembers()
        );
    }

    // ─── Helpers ───

    private List<CollectionStatDto> collectCollectionStats(MongoDatabase db) {
        List<CollectionStatDto> result = new ArrayList<>();
        for (String name : db.listCollectionNames()) {
            try {
                Document stats = db.runCommand(new Document("collStats", name));
                long count = ((Number) stats.getOrDefault("count", 0)).longValue();
                long size = ((Number) stats.getOrDefault("size", 0)).longValue();
                int nIndexes = ((Number) stats.getOrDefault("nindexes", 0)).intValue();
                Instant lastWriteAt = readLastWrite(db.getCollection(name));
                result.add(new CollectionStatDto(name, count, size, nIndexes, lastWriteAt));
            } catch (RuntimeException ignored) {
                // collStats peut échouer sur une vue ou collection verrouillée — on saute.
            }
        }
        // tri par taille desc — la plus grosse en haut.
        result.sort(Comparator.comparingLong(CollectionStatDto::sizeBytes).reversed());
        return result;
    }

    /**
     * Lit la dernière écriture observée d'une collection. Heuristique :
     * on cherche un champ {@code updatedAt} ou {@code createdAt} en triant
     * desc ; si la collection n'a aucun de ces deux champs (ex. index
     * techniques) on renvoie {@code null}.
     */
    private Instant readLastWrite(MongoCollection<Document> coll) {
        for (String field : List.of("updatedAt", "createdAt", "occurredAt", "executedAt", "timestamp")) {
            try {
                Document last = coll.find().sort(Sorts.descending(field)).limit(1).first();
                if (last == null) continue;
                Object value = last.get(field);
                if (value instanceof Date d) return d.toInstant();
                if (value instanceof Instant i) return i;
            } catch (RuntimeException ignored) {
                // champ absent / index manquant — on essaie le suivant.
            }
        }
        return null;
    }

    private List<MigrationEntryDto> collectMigrationHistory(MongoDatabase tenantDb) {
        MongoCollection<Document> changeLog = tenantDb.getCollection("mongockChangeLog");
        // Map changeId → derniere entrée pertinente, pour ne pas dupliquer
        // les sous-événements EXECUTION/BEFORE_EXECUTION/AFTER_EXECUTION.
        Map<String, Document> latestByChangeId = new HashMap<>();
        try {
            for (Document doc : changeLog.find().sort(Sorts.descending("timestamp"))) {
                String changeId = doc.getString("changeId");
                if (changeId == null) continue;
                // Mongock écrit ses propres entrées de bookkeeping
                // (system-change-*, author=mongock, systemChange=true).
                // On les masque — l'utilisateur ne veut voir QUE les
                // changeUnits applicatifs.
                Boolean systemChange = doc.getBoolean("systemChange");
                if (Boolean.TRUE.equals(systemChange)) continue;
                if (changeId.startsWith("system-change-")) continue;
                // On garde la première entrée vue (la plus récente grâce au tri).
                latestByChangeId.putIfAbsent(changeId, doc);
            }
        } catch (RuntimeException ignored) {
            // mongockChangeLog n'existe pas encore — base jamais migrée.
        }

        // Itère sur le catalogue pour produire la liste, dans l'ordre.
        // Catalogue absent → pending ; catalogue présent → status from log.
        List<MigrationEntryDto> result = new ArrayList<>();
        Set<String> catalogIds = new HashSet<>();

        for (MigrationDescriptor desc : CATALOG) {
            catalogIds.add(desc.changeId());
            Document doc = latestByChangeId.get(desc.changeId());
            result.add(toMigrationEntry(desc, doc));
        }

        // Entrées présentes en base mais absentes du catalogue actuel : on
        // les affiche en queue pour traçabilité (ex. migration retirée du code).
        latestByChangeId.forEach((id, doc) -> {
            if (!catalogIds.contains(id)) {
                MigrationDescriptor desc = new MigrationDescriptor(
                        id,
                        "?",
                        doc.getString("author") != null ? doc.getString("author") : "—"
                );
                result.add(toMigrationEntry(desc, doc));
            }
        });

        // tri par ordre asc
        result.sort(Comparator.comparing(MigrationEntryDto::order));
        return result;
    }

    private MigrationEntryDto toMigrationEntry(MigrationDescriptor desc, Document log) {
        if (log == null) {
            return new MigrationEntryDto(
                    desc.changeId(), desc.order(), desc.author(),
                    "pending", null, null, null
            );
        }
        String state = log.getString("state");
        String status = mapMongockState(state);
        Date timestamp = log.getDate("timestamp");
        Long durationMs = log.get("executionMillis") instanceof Number n ? n.longValue() : null;
        String errorMessage = log.getString("errorTrace");
        return new MigrationEntryDto(
                desc.changeId(),
                desc.order(),
                desc.author(),
                status,
                timestamp != null ? timestamp.toInstant() : null,
                durationMs,
                errorMessage
        );
    }

    private static String mapMongockState(String state) {
        if (state == null) return "pending";
        return switch (state) {
            case "EXECUTED" -> "success";
            case "FAILED", "ROLLED_BACK", "ROLLBACK_FAILED" -> "failed";
            case "IGNORED" -> "ignored";
            default -> "pending";
        };
    }

    private static String computeMongockVersion(List<MigrationEntryDto> migrations) {
        // Numéro d'ordre de la dernière migration appliquée avec succès.
        // Si rien d'appliqué : "0".
        return migrations.stream()
                .filter(m -> "success".equals(m.status()))
                .map(MigrationEntryDto::order)
                .reduce((a, b) -> b)  // dernier élément après tri asc
                .orElse("0");
    }

    private static String computeMigrationsHealth(List<MigrationEntryDto> migrations) {
        boolean anyFailed = migrations.stream().anyMatch(m -> "failed".equals(m.status()));
        if (anyFailed) return "failed";
        boolean anyPending = migrations.stream().anyMatch(m -> "pending".equals(m.status()));
        if (anyPending) return "pending";
        return "ok";
    }

    private List<BackupSnapshotDto> collectRecentBackups(UUID tenantId) {
        MongoCollection<Document> backups = mongoClient
                .getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.TENANT_BACKUPS);
        Map<String, Document> dedupe = new LinkedHashMap<>();
        try {
            for (Document doc : backups
                    .find(Filters.eq("tenantId", tenantId))
                    .sort(Sorts.descending("executedAt"))
                    .limit(5)) {
                Object id = doc.get("_id");
                if (id != null) dedupe.putIfAbsent(id.toString(), doc);
            }
        } catch (RuntimeException ignored) {
            // collection vide / inexistante — ok, on renvoie liste vide.
        }
        return dedupe.values().stream()
                .map(TenantTechnicalService::toBackupSnapshot)
                .toList();
    }

    private static BackupSnapshotDto toBackupSnapshot(Document d) {
        Date executedAt = d.getDate("executedAt");
        Number size = (Number) d.getOrDefault("sizeBytes", 0);
        Number duration = (Number) d.getOrDefault("durationMs", 0);
        return new BackupSnapshotDto(
                d.get("_id") != null ? d.get("_id").toString() : null,
                executedAt != null ? executedAt.toInstant() : null,
                d.getString("status") != null ? d.getString("status") : "success",
                size.longValue(),
                d.getString("planAtTime") != null ? d.getString("planAtTime") : "starter",
                duration.longValue(),
                d.getString("errorMessage")
        );
    }
}
