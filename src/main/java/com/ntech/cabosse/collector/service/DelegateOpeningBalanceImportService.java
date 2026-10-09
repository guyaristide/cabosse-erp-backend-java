package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportCommitDto;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportPreviewDto;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportPreviewDto.FieldIssue;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportPreviewDto.Normalized;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportPreviewDto.Row;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportPreviewDto.Status;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceImportRowDto;
import com.ntech.cabosse.collector.dto.DelegateOpeningBalanceUpsertDto;
import com.ntech.cabosse.shared.exception.UserFacingReason;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportParsers;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ntech.cabosse.shared.imports.ImportParsers.normalize;
import static com.ntech.cabosse.shared.imports.ImportParsers.trimOrNull;

/**
 * Reprendre les soldes de début de campagne depuis un fichier.
 *
 * <p>Une coopérative qui démarre arrive avec des dizaines de délégués
 * débiteurs, tenus jusque-là sur un classeur. Les saisir un à un occupe
 * une matinée et la moindre coquille passe inaperçue (demandé le
 * 08/10/2026).</p>
 *
 * <p>Une reprise <strong>corrige</strong> au lieu de s'empiler, comme la
 * saisie à l'écran : la prévisualisation montre donc ce que chaque ligne
 * remplace. Un délégué absent du fichier garde ce qu'il avait, le fichier
 * ne faisant pas autorité sur ce qu'il ne nomme pas.</p>
 */
@ApplicationScoped
public class DelegateOpeningBalanceImportService {

    @Inject SupplierRepository suppliers;
    @Inject DelegateOpeningBalanceService service;

    public DelegateOpeningBalanceImportPreviewDto preview(UUID campaignId,
                                                          List<DelegateOpeningBalanceImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new DelegateOpeningBalanceImportPreviewDto(0, 0, 0, 0, List.of());
        }

        // Les délégués une fois pour tout le fichier : une recherche par
        // ligne ferait autant d'allers-retours que le classeur a de lignes.
        Map<String, SupplierEntity> byCode = new HashMap<>();
        Map<String, SupplierEntity> byName = new HashMap<>();
        for (SupplierEntity s : suppliers.listAll()) {
            if (!s.collector) continue;
            if (s.code != null) byCode.putIfAbsent(normalize(s.code), s);
            if (s.name != null) byName.putIfAbsent(normalize(s.name), s);
        }

        Map<UUID, Integer> firstByDelegate = new HashMap<>();
        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0;
        int invalid = 0;
        int duplicate = 0;

        for (DelegateOpeningBalanceImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            ImportParsers.IssueSink sink = ImportParsers.listSink(issues, FieldIssue::new);

            String code = trimOrNull(raw.delegateCode());
            String name = trimOrNull(raw.delegateName());
            SupplierEntity delegate = null;
            if (code != null) delegate = byCode.get(normalize(code));
            if (delegate == null && name != null) delegate = byName.get(normalize(name));
            if (delegate == null) {
                // Un délégué n'est pas créé au passage : l'ouvrir ici
                // poserait une fiche sans section ni taux, et la reprise
                // d'un solde n'est pas le bon moment pour le faire.
                issues.add(new FieldIssue("delegate",
                        Messages.msg("m.dob-delegate-unknown",
                                code != null ? code : name != null ? name : "")));
            }

            BigDecimal amount = ImportParsers.parseDecimal(raw.amount(), sink, "amount");
            if (amount == null && issues.stream().noneMatch(i -> "amount".equals(i.field()))) {
                issues.add(new FieldIssue("amount", Messages.msg("m.dob-amount-required")));
            }
            String notes = trimOrNull(raw.notes());

            Status status;
            BigDecimal existing = delegate != null
                    ? service.amountFor(delegate.id, campaignId) : null;
            if (!issues.isEmpty()) {
                status = Status.INVALID;
                invalid++;
            } else if (firstByDelegate.containsKey(delegate.id)) {
                // Deux lignes pour un même délégué : la seconde écraserait
                // la première sans que rien ne le dise.
                issues.add(new FieldIssue("delegate",
                        Messages.msg("m.dob-delegate-twice", delegate.name,
                                String.valueOf(firstByDelegate.get(delegate.id)))));
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else {
                firstByDelegate.put(delegate.id, raw.rowNumber());
                status = Status.READY;
                ready++;
            }

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(
                            delegate != null ? delegate.id : null,
                            delegate != null ? delegate.code : code,
                            delegate != null ? delegate.name : name,
                            amount, existing, notes),
                    issues));
        }
        return new DelegateOpeningBalanceImportPreviewDto(
                input.size(), ready, invalid, duplicate, rows);
    }

    public DelegateOpeningBalanceImportCommitDto commit(UUID campaignId,
                                                        List<DelegateOpeningBalanceImportRowDto> input) {
        DelegateOpeningBalanceImportPreviewDto preview = preview(campaignId, input);
        List<UUID> applied = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                service.upsert(n.delegateSupplierId(),
                        new DelegateOpeningBalanceUpsertDto(campaignId, n.amount(), n.notes()));
                applied.add(n.delegateSupplierId());
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server",
                        UserFacingReason.of(e, "ligne " + row.rowNumber())));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, n, issues));
            }
        }
        return new DelegateOpeningBalanceImportCommitDto(
                preview.totalRows(), applied.size(), skipped.size(), applied, skipped);
    }
}
