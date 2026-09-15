package com.ntech.cabosse.importjournal.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.importjournal.dto.ImportRunDetailDto;
import com.ntech.cabosse.importjournal.dto.ImportRunSummaryDto;
import com.ntech.cabosse.importjournal.dto.ReasonGroupDto;
import com.ntech.cabosse.importjournal.entity.ImportDecision;
import com.ntech.cabosse.importjournal.entity.ImportRejection;
import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relit le journal des imports d'une structure, depuis l'administration
 * de la plateforme.
 *
 * <p>La base de la structure s'ouvre par son nom, comme le fait déjà le
 * diagnostic : le fournisseur de base habituel suit le jeton de l'appelant
 * et renverrait le plan de contrôle pour un administrateur de plateforme.
 * Chaque consultation est journalisée, comme tout accès inter-structure.</p>
 */
@ApplicationScoped
public class ImportRunReadService {

    /** Au-delà, on ne lit plus une liste de refus, on la subit. */
    private static final int MAX_SAMPLE_ROWS = 8;

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;

    public List<ImportRunSummaryDto> search(UUID tenantId, String domain, int skip, int limit,
                                            String actorEmail) {
        TenantEntity tenant = tenantOrFail(tenantId);
        var filter = domain == null || domain.isBlank()
                ? new Document() : Filters.eq("domain", domain);
        List<ImportRunSummaryDto> out = new ArrayList<>();
        for (Document d : collection(tenant).find(filter)
                .sort(Sorts.descending("at"))
                .skip(Math.max(0, skip))
                .limit(Math.max(1, limit))) {
            out.add(summaryOf(d));
        }
        record(tenant, actorEmail, "liste des imports");
        return out;
    }

    public ImportRunDetailDto get(UUID tenantId, UUID runId, String actorEmail) {
        TenantEntity tenant = tenantOrFail(tenantId);
        Document d = collection(tenant).find(Filters.eq("_id", runId)).first();
        if (d == null) {
            throw new NotFoundException(Messages.msg("m.imr-run-not-found"));
        }
        List<ImportRejection> rejections = rejectionsOf(d);
        record(tenant, actorEmail, "import " + d.getString("domain"));
        return new ImportRunDetailDto(
                summaryOf(d),
                groupByReason(rejections),
                rejections,
                decisionsOf(d),
                d.get("rowsRejected", 0) > rejections.size());
    }

    /**
     * Les motifs, du plus fréquent au plus rare.
     *
     * <p>Un fichier refusé l'est presque toujours pour une poignée de
     * raisons répétées. Les lire groupées épargne le parcours ligne à
     * ligne, et fait apparaître d'un coup d'œil la colonne à reprendre.</p>
     */
    private List<ReasonGroupDto> groupByReason(List<ImportRejection> rejections) {
        Map<String, List<Integer>> rows = new LinkedHashMap<>();
        for (ImportRejection r : rejections) {
            rows.computeIfAbsent(r.reason == null ? "" : r.reason, k -> new ArrayList<>())
                    .add(r.rowNumber);
        }
        List<ReasonGroupDto> out = new ArrayList<>();
        rows.forEach((reason, lines) -> out.add(new ReasonGroupDto(
                reason, lines.size(),
                lines.stream().limit(MAX_SAMPLE_ROWS).toList())));
        out.sort(Comparator.comparingInt(ReasonGroupDto::count).reversed());
        return out;
    }

    private ImportRunSummaryDto summaryOf(Document d) {
        Object decisions = d.get("decisions");
        int decisionCount = decisions instanceof List<?> list ? list.size() : 0;
        return new ImportRunSummaryDto(
                d.get("_id", UUID.class),
                d.getString("domain"),
                d.getString("phase"),
                instantOf(d.get("at")),
                d.getString("actorEmail"),
                d.getString("fileName"),
                d.get("rowsReceived", 0),
                d.get("rowsCreated", 0),
                d.get("rowsSkipped", 0),
                d.get("rowsRejected", 0),
                d.get("durationMs", 0L),
                decisionCount);
    }

    /**
     * Le pilote rend les horodatages en {@code Date}, jamais en
     * {@code Instant} : lire un document brut n'a pas les conversions
     * que le mapping d'entité applique.
     */
    private Instant instantOf(Object value) {
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        return null;
    }

    private List<ImportRejection> rejectionsOf(Document d) {
        List<ImportRejection> out = new ArrayList<>();
        if (!(d.get("rejections") instanceof List<?> raw)) return out;
        for (Object o : raw) {
            if (o instanceof Document r) {
                out.add(new ImportRejection(
                        r.get("rowNumber", 0), r.getString("reason"), r.getString("rowHint")));
            }
        }
        return out;
    }

    private List<ImportDecision> decisionsOf(Document d) {
        List<ImportDecision> out = new ArrayList<>();
        if (!(d.get("decisions") instanceof List<?> raw)) return out;
        for (Object o : raw) {
            if (o instanceof Document r) {
                out.add(new ImportDecision(
                        r.getString("kind"), r.getString("field"),
                        r.getString("given"), r.getString("applied"),
                        r.get("occurrences", 0)));
            }
        }
        return out;
    }

    private com.mongodb.client.MongoCollection<Document> collection(TenantEntity tenant) {
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);
        return db.getCollection(ImportRunEntity.COLLECTION);
    }

    private TenantEntity tenantOrFail(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }
        return tenant;
    }

    /** Lire les données d'une structure se trace, même en diagnostic. */
    private void record(TenantEntity tenant, String actorEmail, String what) {
        audit.event(AuditEventType.CROSS_TENANT_ACCESS)
                .actorEmail(actorEmail)
                .target("import_run", tenant.id.toString(), tenant.name)
                .tenant(tenant.id, tenant.name)
                .description("Consultation du journal des imports : " + what)
                .record();
    }
}
