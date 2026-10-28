package com.ntech.cabosse.logs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.health.BuildInfo;
import com.ntech.cabosse.logs.dto.LogsArchiveManifest;
import com.ntech.cabosse.logs.repository.TenantLogsRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditRepository;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.shared.trace.RequestTraceRepository;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.StreamingOutput;
import org.bson.Document;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * L'archive des journaux d'une structure (SEC-16, décision DEC-51).
 *
 * <p>Une structure contrôlée doit pouvoir produire, sur une période, tout
 * ce que le serveur a retenu d'elle : les décisions du journal d'audit,
 * les requêtes tracées, les imports, les envois et les clés d'idempotence.
 * Chaque jeu sort en CSV pour le tableur et en JSON ligne à ligne pour la
 * relecture complète ; le manifeste dit ce que l'archive contient.</p>
 *
 * <p>Écrite en flux, directement dans la réponse : rien n'est accumulé en
 * mémoire ni posé sur le disque du serveur. Le téléchargement lui-même est
 * un événement de sécurité et se journalise comme tel.</p>
 */
@ApplicationScoped
public class TenantLogsArchiveService {

    public static final String MANIFEST = "manifest.json";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_FR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    /** Au-delà, le corps d'un envoi n'est plus un journal, c'est le message lui-même. */
    private static final int BODY_MAX = 500;

    private static final List<String> AUDIT_COLUMNS = List.of(
            "occurredAt", "category", "eventType", "actorEmail", "impersonatedBy",
            "targetType", "targetId", "targetLabel", "description", "ipAddress");
    private static final List<String> TRACE_COLUMNS = List.of(
            "occurredAt", "requestId", "method", "path", "query", "status", "outcome",
            "durationMs", "actorEmail", "impersonatedBy", "ipAddress", "userAgent",
            "idempotencyKey", "responseBytes");
    private static final List<String> IMPORT_COLUMNS = List.of(
            "at", "domain", "phase", "status", "actorEmail", "fileName", "rowsReceived",
            "rowsCreated", "rowsSkipped", "rowsRejected", "durationMs", "failure",
            "undoneAt", "undoneBy", "rowsUndone");
    private static final List<String> NOTIFICATION_COLUMNS = List.of(
            "createdAt", "channel", "usage", "eventType", "subjectRef", "target", "subject",
            "status", "attempts", "providerCode", "providerMessageId", "lastError", "sentAt");
    private static final List<String> IDEMPOTENCY_COLUMNS = List.of(
            "key", "method", "path", "statusCode", "actorEmail", "createdAt");

    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;
    @Inject AuditRepository auditEvents;
    @Inject TenantLogsRepository logs;
    @Inject RequestTraceRepository traces;
    @Inject BuildInfo buildInfo;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper json;

    /** L'archive de la structure de l'appelant. */
    public LogsArchiveDownload forCurrentTenant(String fromRaw, String toRaw) {
        LogsPeriod period = LogsPeriod.parse(fromRaw, toRaw);
        TenantEntity tenant = tenantOrFail(tenantContext.tenantId());
        return download(tenant, period);
    }

    /**
     * L'archive d'une structure quelconque, pour l'éditeur.
     *
     * <p>Un agent de la plateforme lit les journaux d'un client : l'accès
     * inter-structure se trace avant même que l'archive commence.</p>
     */
    public LogsArchiveDownload forTenant(UUID tenantId, String fromRaw, String toRaw) {
        LogsPeriod period = LogsPeriod.parse(fromRaw, toRaw);
        TenantEntity tenant = tenantOrFail(tenantId);
        audit.event(AuditEventType.CROSS_TENANT_ACCESS)
                .actorEmail(actor())
                .target("tenant_logs_archive", tenant.id.toString(), tenant.name)
                .tenant(tenant.id, tenant.name)
                .description("Archive des journaux de « " + tenant.name + " » demandée par l'éditeur")
                .payload(Map.of("from", period.from().toString(), "to", period.to().toString()))
                .record();
        return download(tenant, period);
    }

    private LogsArchiveDownload download(TenantEntity tenant, LogsPeriod period) {
        String actor = actor();
        String filename = "journaux-" + tenant.slug + "-" + DAY.format(period.from())
                + "-" + DAY.format(period.to()) + ".zip";
        StreamingOutput body = out -> write(tenant, period, actor, out);
        return new LogsArchiveDownload(filename, body);
    }

    /** Écrit l'archive dans le flux et journalise le téléchargement. Rend les comptes par fichier. */
    Map<String, Long> write(TenantEntity tenant, LogsPeriod period, String actor,
                            OutputStream out) throws IOException {
        Instant from = period.from();
        Instant to = period.to();
        String db = tenant.databaseName;
        Map<String, Long> counts = new LinkedHashMap<>();

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            csv(zip, "audit-events.csv", AUDIT_COLUMNS,
                    auditEvents.rawForTenant(tenant.id, from, to), this::auditRow, counts);
            jsonl(zip, "audit-events.jsonl",
                    auditEvents.rawForTenant(tenant.id, from, to), UnaryOperator.identity(), counts);

            csv(zip, "request-traces.csv", TRACE_COLUMNS,
                    traces.list(db, from, to), this::traceRow, counts);
            jsonl(zip, "request-traces.jsonl",
                    traces.list(db, from, to), UnaryOperator.identity(), counts);

            csv(zip, "import-runs.csv", IMPORT_COLUMNS,
                    logs.importRuns(db, from, to), this::importRow, counts);
            jsonl(zip, "import-runs.jsonl",
                    logs.importRuns(db, from, to), UnaryOperator.identity(), counts);

            csv(zip, "notifications.csv", NOTIFICATION_COLUMNS,
                    logs.notifications(db, from, to), this::notificationRow, counts);
            jsonl(zip, "notifications.jsonl",
                    logs.notifications(db, from, to), TenantLogsArchiveService::withShortBody, counts);

            // Jamais la réponse rejouée : elle est exclue à la lecture.
            csv(zip, "idempotency.csv", IDEMPOTENCY_COLUMNS,
                    logs.idempotencyKeys(db, from, to), this::idempotencyRow, counts);

            LogsArchiveManifest manifest = new LogsArchiveManifest(
                    tenant.id, tenant.slug, tenant.name, from, to, Instant.now(), actor,
                    buildInfo.version(), counts);
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        audit.event(AuditEventType.LOGS_ARCHIVE_DOWNLOADED)
                .actorEmail(actor)
                .target("tenant_logs_archive", tenant.id.toString(), tenant.name)
                .tenant(tenant.id, tenant.name)
                .description("Archive des journaux de « " + tenant.name + " » du "
                        + DAY_FR.format(from) + " au " + DAY_FR.format(to)
                        + " téléchargée (" + total + " ligne" + (total > 1 ? "s" : "") + ")")
                .payload(Map.of("from", from.toString(), "to", to.toString(), "counts", counts))
                .record();
        return counts;
    }

    // ─── Lignes CSV ─────────────────────────────────────────────────

    private Object[] auditRow(Document d) {
        return new Object[] {
                d.get("occurredAt"), d.get("category"), d.get("eventType"), d.get("actorEmail"),
                d.get("actorImpersonatedBy"), d.get("targetType"), d.get("targetId"),
                d.get("targetLabel"), d.get("description"), d.get("ipAddress") };
    }

    private Object[] traceRow(Document d) {
        return new Object[] {
                d.get("occurredAt"), d.get("requestId"), d.get("method"), d.get("path"),
                d.get("query"), d.get("status"), d.get("outcome"), d.get("durationMs"),
                d.get("actorEmail"), d.get("impersonatedBy"), d.get("ipAddress"),
                d.get("userAgent"), d.get("idempotencyKey"), d.get("responseBytes") };
    }

    private Object[] importRow(Document d) {
        return new Object[] {
                d.get("at"), d.get("domain"), d.get("phase"), d.get("status"), d.get("actorEmail"),
                d.get("fileName"), d.get("rowsReceived"), d.get("rowsCreated"), d.get("rowsSkipped"),
                d.get("rowsRejected"), d.get("durationMs"), d.get("failure"), d.get("undoneAt"),
                d.get("undoneBy"), d.get("rowsUndone") };
    }

    private Object[] notificationRow(Document d) {
        return new Object[] {
                d.get("createdAt"), d.get("channel"), d.get("usage"), d.get("eventType"),
                d.get("subjectRef"), d.get("target"), d.get("subject"), d.get("status"),
                d.get("attempts"), d.get("providerCode"), d.get("providerMessageId"),
                d.get("lastError"), d.get("sentAt") };
    }

    private Object[] idempotencyRow(Document d) {
        return new Object[] {
                d.get("_id"), d.get("method"), d.get("path"), d.get("statusCode"),
                d.get("actorEmail"), d.get("createdAt") };
    }

    /** Le corps d'un envoi, coupé : le journal dit qu'un message est parti, pas tout le message. */
    private static Document withShortBody(Document d) {
        Object body = d.get("body");
        if (!(body instanceof String s) || s.length() <= BODY_MAX) return d;
        Document copy = new Document(d);
        copy.put("body", s.substring(0, BODY_MAX) + "…");
        copy.put("bodyTruncated", true);
        return copy;
    }

    // ─── Écriture d'une entrée ──────────────────────────────────────

    private void csv(ZipOutputStream zip, String entry, List<String> columns,
                     Iterable<Document> documents, Function<Document, Object[]> row,
                     Map<String, Long> counts) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        CsvLines csv = new CsvLines(zip, columns);
        for (Document d : documents) csv.row(row.apply(d));
        zip.closeEntry();
        counts.put(entry, csv.rows());
    }

    private void jsonl(ZipOutputStream zip, String entry, Iterable<Document> documents,
                       UnaryOperator<Document> shape, Map<String, Long> counts) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        JsonLines lines = new JsonLines(zip, json);
        for (Document d : documents) lines.row(shape.apply(d));
        zip.closeEntry();
        counts.put(entry, lines.rows());
    }

    private TenantEntity tenantOrFail(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }
        return tenant;
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
