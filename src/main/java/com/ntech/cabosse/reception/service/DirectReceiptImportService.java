package com.ntech.cabosse.reception.service;

import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.reception.dto.DirectReceiptImportCommitResponseDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto.FieldIssue;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto.Group;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto.Normalized;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto.Row;
import com.ntech.cabosse.reception.dto.DirectReceiptImportPreviewDto.Status;
import com.ntech.cabosse.reception.dto.DirectReceiptImportRequestDto;
import com.ntech.cabosse.reception.dto.DirectReceiptImportRowDto;
import com.ntech.cabosse.reception.dto.DirectReceiptLineDto;
import com.ntech.cabosse.reception.dto.DirectReceiptResponseDto;
import com.ntech.cabosse.reception.dto.DirectReceiptUpsertDto;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.entity.DirectReceiptStatus;
import com.ntech.cabosse.reception.repository.DirectReceiptRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.supplier.service.SupplierService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.ntech.cabosse.shared.imports.ImportParsers.parseDecimal;
import static com.ntech.cabosse.shared.imports.ImportParsers.slugify;
import static com.ntech.cabosse.shared.imports.ImportParsers.trimOrNull;

/**
 * Orchestre l'import en masse de réceptions directes depuis un fichier
 * (CSV/Excel parsé côté client).
 *
 * <p>Décision structurante : un fichier porte <strong>un seul</strong>
 * article (choisi en en-tête de la requête, jamais dans une cellule). Les
 * lignes sont regroupées par <em>date</em> ; chaque date distincte donne
 * <strong>une</strong> session {@code DirectReceiptEntity}.</p>
 *
 * <p>Le commit auto-crée les producteurs inconnus avec des valeurs par
 * défaut (mêmes conventions que l'import fournisseurs). Aucune ligne en
 * erreur ne bloque les autres : commit partiel.</p>
 */
@ApplicationScoped
public class DirectReceiptImportService {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.FRANCE),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE),
            DateTimeFormatter.ofPattern("d-M-yyyy", Locale.FRANCE),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.FRANCE),
            DateTimeFormatter.ISO_LOCAL_DATE,                              // yyyy-MM-dd
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.FRANCE)
    };

    @Inject ArticleRepository articles;
    @Inject SupplierRepository suppliers;
    @Inject SupplierService supplierService;
    @Inject DirectReceiptRepository receipts;
    @Inject DirectReceiptService receiptService;

    // ─── Preview ────────────────────────────────────────────────────

    public DirectReceiptImportPreviewDto preview(DirectReceiptImportRequestDto request) {
        ArticleEntity article = loadArticle(request.articleId());
        List<DirectReceiptImportRowDto> input = request.rows();
        if (input == null || input.isEmpty()) {
            return new DirectReceiptImportPreviewDto(0, 0, 0, 0, 0, List.of(), List.of());
        }

        // Snapshot du référentiel fournisseurs (matchage exact par code,
        // sinon fuzzy par nom normalisé). Indexes case-insensitive.
        Map<String, SupplierEntity> byCode = new HashMap<>();
        Map<String, SupplierEntity> byNormalizedName = new HashMap<>();
        for (SupplierEntity s : suppliers.listAll()) {
            if (!s.active) continue;
            if (s.code != null) byCode.put(s.code.toLowerCase(Locale.ROOT), s);
            if (s.name != null) byNormalizedName.put(normalize(s.name), s);
        }

        // Empreintes des RDs existantes pour cet article — détection des
        // doublons inter-DB (même date + producteur + qté + PU).
        java.util.Set<String> existingFingerprints = new java.util.HashSet<>();
        for (DirectReceiptEntity r : receipts.listAll()) {
            if (!article.id.equals(r.articleId)) continue;
            if (r.status == DirectReceiptStatus.CANCELLED) continue;
            for (DirectReceiptLine line : r.lines) {
                existingFingerprints.add(fingerprint(
                        r.receivedDate, line.supplierId, line.quantity, line.unitPriceFcfa
                ));
            }
        }

        // Empreintes vues dans le fichier lui-même (intra-fichier).
        Map<String, Integer> firstOccurrence = new HashMap<>();

        // Producteurs à auto-créer — on garde une trace pour ne compter qu'une
        // seule création par clé (code ou slug du nom).
        Map<String, String> supplierToCreateByKey = new LinkedHashMap<>(); // key → display name

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (DirectReceiptImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            LocalDate date = parseDate(raw.date(), request.defaultDate(), issues);
            BigDecimal qty = parseDecimal(raw.quantity(), (f, m) -> issues.add(new FieldIssue(f, m)), "quantity");
            if (qty != null && qty.signum() <= 0) {
                issues.add(new FieldIssue("quantity", Messages.msg("m.imp-quantity-positive-required")));
                qty = null;
            }
            BigDecimal pu = parseDecimal(raw.unitPriceFcfa(), (f, m) -> issues.add(new FieldIssue(f, m)), "unitPriceFcfa");
            if (pu != null && pu.signum() < 0) {
                issues.add(new FieldIssue("unitPriceFcfa", Messages.msg("m.imp-unit-price-negative")));
                pu = null;
            }

            String producerCode = trimOrNull(raw.producerCode());
            String producerName = trimOrNull(raw.producerName());
            String deliveryNoteRef = trimOrNull(raw.deliveryNoteRef());
            String notes = trimOrNull(raw.notes());

            // ── Résolution producteur ──
            UUID resolvedId = null;
            String resolvedCode = null;
            String resolvedName = null;
            boolean willBeCreated = false;
            String supplierKey = null;

            if (producerCode == null && producerName == null) {
                issues.add(new FieldIssue("producer", Messages.msg("m.imp-producer-required")));
            } else {
                // 1) Match exact par code
                if (producerCode != null) {
                    SupplierEntity match = byCode.get(producerCode.toLowerCase(Locale.ROOT));
                    if (match != null) {
                        resolvedId = match.id;
                        resolvedCode = match.code;
                        resolvedName = match.name;
                    }
                }
                // 2) Match par nom normalisé
                if (resolvedId == null && producerName != null) {
                    SupplierEntity match = byNormalizedName.get(normalize(producerName));
                    if (match != null) {
                        resolvedId = match.id;
                        resolvedCode = match.code;
                        resolvedName = match.name;
                    }
                }
                // 3) Sinon → marqué pour auto-création
                if (resolvedId == null) {
                    String targetName = producerName != null ? producerName
                            : (producerCode != null ? producerCode : null);
                    String targetCode = producerCode != null ? producerCode
                            : slugify(targetName);
                    if (targetCode == null || targetCode.isBlank()) {
                        issues.add(new FieldIssue("producer", Messages.msg("m.imp-producer-code-ungeneratable")));
                    } else {
                        // Valide le code par rapport au pattern du DTO
                        // (minuscules/chiffres/tirets, 2-40)
                        String safe = targetCode.toLowerCase(Locale.ROOT)
                                .replaceAll("[^a-z0-9-]", "-")
                                .replaceAll("-+", "-")
                                .replaceAll("^-+|-+$", "");
                        if (safe.length() < 2) {
                            issues.add(new FieldIssue("producer",
                                    Messages.msg("m.imp-producer-code-too-short", targetCode)));
                        } else {
                            if (safe.length() > 40) safe = safe.substring(0, 40);
                            // Re-vérifie qu'aucun fournisseur existant n'a déjà
                            // ce code (un cas avec différences d'accents pourrait
                            // déjà être dans byCode après slugification).
                            if (byCode.containsKey(safe)) {
                                SupplierEntity match = byCode.get(safe);
                                resolvedId = match.id;
                                resolvedCode = match.code;
                                resolvedName = match.name;
                            } else {
                                supplierKey = safe;
                                resolvedCode = safe;
                                resolvedName = targetName;
                                willBeCreated = true;
                                supplierToCreateByKey.putIfAbsent(safe, targetName);
                            }
                        }
                    }
                }
            }

            BigDecimal total = (qty != null && pu != null)
                    ? qty.multiply(pu).setScale(2, RoundingMode.HALF_UP)
                    : null;

            Normalized normalized = new Normalized(
                    date, resolvedId, resolvedCode, resolvedName, willBeCreated,
                    qty, pu, total, deliveryNoteRef, notes
            );

            // ── Statut + doublons ──
            Status status;
            if (!issues.isEmpty() || date == null || qty == null || pu == null
                    || (resolvedId == null && supplierKey == null)) {
                status = Status.INVALID;
                invalid++;
            } else {
                // Empreinte stable : la clé fournisseur fallback sur le code
                // (qui sera l'ID après création) pour cohérence intra-fichier.
                String supplierFp = resolvedId != null ? resolvedId.toString() : supplierKey;
                String fp = fingerprint(date, supplierFp, qty, pu);

                if (existingFingerprints.contains(
                        fingerprint(date, resolvedId, qty, pu))) {
                    issues.add(new FieldIssue("duplicate", Messages.msg("m.imp-receipt-duplicate-in-db")));
                    status = Status.DUPLICATE_IN_DB;
                    duplicate++;
                } else if (firstOccurrence.containsKey(fp)) {
                    issues.add(new FieldIssue("duplicate", Messages.msg("m.imp-row-duplicate-at-line",
                            String.valueOf(firstOccurrence.get(fp)))));
                    status = Status.DUPLICATE_IN_FILE;
                    duplicate++;
                } else {
                    firstOccurrence.put(fp, raw.rowNumber());
                    status = Status.READY;
                    ready++;
                }
            }

            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        // Groupes : 1 par date distincte parmi les lignes READY
        Map<LocalDate, Group> groupAccum = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.status() != Status.READY) continue;
            LocalDate d = row.normalized().date();
            BigDecimal lineTotal = row.normalized().totalLineFcfa();
            Group prev = groupAccum.get(d);
            if (prev == null) {
                groupAccum.put(d, new Group(d, 1, lineTotal));
            } else {
                groupAccum.put(d, new Group(d, prev.lineCount() + 1,
                        prev.subtotalHtFcfa().add(lineTotal)));
            }
        }
        List<Group> groups = new ArrayList<>(groupAccum.values());
        groups.sort(Comparator.comparing(Group::date));

        return new DirectReceiptImportPreviewDto(
                input.size(), ready, invalid, duplicate,
                supplierToCreateByKey.size(),
                groups, rows
        );
    }

    // ─── Commit ─────────────────────────────────────────────────────

    public DirectReceiptImportCommitResponseDto commit(DirectReceiptImportRequestDto request, UUID siteId) {
        ArticleEntity article = loadArticle(request.articleId());
        DirectReceiptImportPreviewDto preview = preview(request);

        // 1) Auto-création des producteurs nécessaires
        //    Clé : ce qu'on avait stocké comme "supplierKey" (slug du code).
        Map<String, UUID> createdSupplierByKey = new HashMap<>();
        List<UUID> createdSupplierIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            Normalized n = row.normalized();
            if (row.status() != Status.READY) {
                skipped.add(row);
                continue;
            }
            if (!n.supplierWillBeCreated()) continue;

            String key = n.resolvedSupplierCode();
            if (createdSupplierByKey.containsKey(key)) continue;
            try {
                SupplierResponseDto s = supplierService.create(new SupplierUpsertDto(
                        key,
                        n.resolvedSupplierName() != null ? n.resolvedSupplierName() : key,
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null
                ));
                createdSupplierByKey.put(key, s.id());
                createdSupplierIds.add(s.id());
            } catch (RuntimeException ex) {
                // Création refusée (conflit ou validation) — toutes les lignes
                // dépendant de ce producteur seront marquées INVALID au moment
                // de la création de session (faute d'ID résolu).
            }
        }

        // 2) Groupement par date des lignes READY (en re-vérifiant la résolution
        //    du producteur) puis création des sessions.
        Map<LocalDate, List<RowResolved>> byDate = new LinkedHashMap<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY) continue;
            Normalized n = row.normalized();
            UUID supplierId = n.resolvedSupplierId();
            if (supplierId == null && n.supplierWillBeCreated()) {
                supplierId = createdSupplierByKey.get(n.resolvedSupplierCode());
            }
            if (supplierId == null) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("producer",
                        Messages.msg("m.imp-producer-creation-failed", n.resolvedSupplierCode())));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, n, issues));
                continue;
            }
            byDate.computeIfAbsent(n.date(), k -> new ArrayList<>())
                    .add(new RowResolved(row, supplierId));
        }

        // 3) Création des sessions
        List<String> createdRefs = new ArrayList<>();
        List<UUID> createdSessionIds = new ArrayList<>();
        int committedRows = 0;
        for (Map.Entry<LocalDate, List<RowResolved>> entry : byDate.entrySet()) {
            LocalDate date = entry.getKey();
            List<RowResolved> rows = entry.getValue();
            List<DirectReceiptLineDto> lines = new ArrayList<>(rows.size());
            for (RowResolved rr : rows) {
                Normalized n = rr.row.normalized();
                lines.add(new DirectReceiptLineDto(
                        rr.supplierId,
                        n.quantity(),
                        n.unitPriceFcfa(),
                        n.deliveryNoteRef(),
                        n.notes(),
                        null  // paiement saisi ultérieurement dans l'UI
                ));
            }
            DirectReceiptUpsertDto upsert = new DirectReceiptUpsertDto(
                    article.id, date, null, lines, null,
                    // Pas de TVA imposée par l'import — la majorité des RD
                    // sont auprès de producteurs non assujettis. Si besoin,
                    // l'opérateur édite la RD juste après l'import.
                    null, null
            );
            try {
                DirectReceiptResponseDto created = receiptService.create(upsert, siteId);
                createdRefs.add(created.ref());
                createdSessionIds.add(created.id());
                committedRows += lines.size();
            } catch (RuntimeException ex) {
                // Session refusée → toutes ses lignes deviennent skipped
                for (RowResolved rr : rows) {
                    List<FieldIssue> issues = new ArrayList<>(rr.row.issues());
                    issues.add(new FieldIssue("server", ex.getMessage()));
                    skipped.add(new Row(rr.row.rowNumber(), Status.INVALID,
                            rr.row.normalized(), issues));
                }
            }
        }

        return new DirectReceiptImportCommitResponseDto(
                preview.totalRows(),
                createdRefs.size(),
                committedRows,
                skipped.size(),
                createdSupplierIds.size(),
                createdRefs,
                createdSessionIds,
                createdSupplierIds,
                skipped
        );
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private record RowResolved(Row row, UUID supplierId) {}

    private ArticleEntity loadArticle(UUID id) {
        ArticleEntity a = articles.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.rcv-article-not-found", id))
        );
        if (!a.active) throw new BusinessException(Messages.msg("m.rcv-article-disabled", a.name));
        if (!a.stockable) throw new BusinessException(Messages.msg("m.rcv-article-not-stockable", a.name));
        return a;
    }

    private static LocalDate parseDate(String raw, LocalDate fallback, List<FieldIssue> issues) {
        String s = trimOrNull(raw);
        if (s == null) {
            if (fallback != null) return fallback;
            issues.add(new FieldIssue("date", Messages.msg("m.imp-date-required-or-default")));
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(s, f); }
            catch (Exception ignored) {}
        }
        issues.add(new FieldIssue("date", Messages.msg("m.imp-date-unrecognised", raw)));
        return null;
    }

    private static String fingerprint(LocalDate date, Object supplierRef,
                                      BigDecimal qty, BigDecimal pu) {
        String d = date != null ? date.toString() : "?";
        String s = supplierRef != null ? supplierRef.toString() : "?";
        String q = qty != null ? qty.stripTrailingZeros().toPlainString() : "?";
        String p = pu != null ? pu.stripTrailingZeros().toPlainString() : "?";
        return d + "|" + s + "|" + q + "|" + p;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
