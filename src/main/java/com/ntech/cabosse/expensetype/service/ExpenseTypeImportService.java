package com.ntech.cabosse.expensetype.service;

import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportCommitResponseDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto.FieldIssue;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto.Normalized;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto.Row;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportPreviewDto.Status;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeImportRowDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeResponseDto;
import com.ntech.cabosse.expensetype.dto.ExpenseTypeUpsertDto;
import com.ntech.cabosse.expensetype.entity.ExpenseTypeEntity;
import com.ntech.cabosse.expensetype.repository.ExpenseTypeRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ntech.cabosse.shared.imports.ImportParsers.normalize;
import static com.ntech.cabosse.shared.imports.ImportParsers.slugify;
import static com.ntech.cabosse.shared.imports.ImportParsers.trimOrNull;

@ApplicationScoped
public class ExpenseTypeImportService {

    @Inject ExpenseTypeRepository repo;
    @Inject ExpenseTypeService service;

    public ExpenseTypeImportPreviewDto preview(List<ExpenseTypeImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new ExpenseTypeImportPreviewDto(0, 0, 0, 0, List.of());
        }
        Set<String> existingCodes = new HashSet<>();
        for (ExpenseTypeEntity e : repo.listAll()) {
            if (e.code != null) existingCodes.add(e.code.toLowerCase(Locale.ROOT));
        }
        Map<String, Integer> firstByCode = new HashMap<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (ExpenseTypeImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            String name = trimOrNull(raw.name());
            if (name == null) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-required")));

            String code = trimOrNull(raw.code());
            if (code != null && !code.matches("[a-z0-9-]{2,40}")) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-code-invalid-lowercase")));
            }
            String resolvedCode = (code != null) ? code : (name != null ? slugify(name) : null);

            String category = parseCategory(raw.category(), issues);

            String syscohada = trimOrNull(raw.syscohadaAccount());
            if (syscohada != null && !syscohada.matches("\\d{2,8}")) {
                issues.add(new FieldIssue("syscohadaAccount", Messages.msg("m.imp-syscohada-account-digits")));
            }
            String description = trimOrNull(raw.description());

            Status status;
            if (!issues.isEmpty()) { status = Status.INVALID; invalid++; }
            else if (resolvedCode != null && existingCodes.contains(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-code-already-used", resolvedCode)));
                status = Status.DUPLICATE_IN_DB; duplicate++;
            } else if (resolvedCode != null && firstByCode.containsKey(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-code-already-at-line", resolvedCode,
                        String.valueOf(firstByCode.get(resolvedCode.toLowerCase(Locale.ROOT))))));
                status = Status.DUPLICATE_IN_FILE; duplicate++;
            } else {
                if (resolvedCode != null) firstByCode.put(resolvedCode.toLowerCase(Locale.ROOT), raw.rowNumber());
                status = Status.READY; ready++;
            }

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(resolvedCode, name, category, syscohada, description),
                    issues));
        }
        return new ExpenseTypeImportPreviewDto(input.size(), ready, invalid, duplicate, rows);
    }

    public ExpenseTypeImportCommitResponseDto commit(List<ExpenseTypeImportRowDto> input) {
        ExpenseTypeImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) { skipped.add(row); continue; }
            Normalized n = row.normalized();
            try {
                ExpenseTypeUpsertDto payload = new ExpenseTypeUpsertDto(
                        n.code(), n.name(), n.description(), n.category(), n.syscohadaAccount()
                );
                ExpenseTypeResponseDto created = service.create(payload);
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(), issues));
            }
        }
        return new ExpenseTypeImportCommitResponseDto(
                preview.totalRows(), createdIds.size(), skipped.size(), createdIds, skipped
        );
    }

    /**
     * Catégorie standard de dépense. Accepte les codes techniques ou les
     * libellés FR usuels.
     */
    private static String parseCategory(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        String n = normalize(raw);
        return switch (n) {
            case "logistics", "logistique" -> "LOGISTICS";
            case "utilities", "services" -> "UTILITIES";
            case "admin", "administratif" -> "ADMIN";
            case "marketing", "marketing commercial" -> "MARKETING";
            case "personnel" -> "PERSONNEL";
            case "financial", "financier" -> "FINANCIAL";
            case "other", "autre" -> "OTHER";
            default -> {
                issues.add(new FieldIssue("category", Messages.msg("m.imp-expense-category-unknown", raw)));
                yield null;
            }
        };
    }
}
