package com.ntech.cabosse.supplier.service;

import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportParsers;
import com.ntech.cabosse.supplier.dto.SupplierImportCommitResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto.FieldIssue;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto.Normalized;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto.Row;
import com.ntech.cabosse.supplier.dto.SupplierImportPreviewDto.Status;
import com.ntech.cabosse.supplier.dto.SupplierImportRowDto;
import com.ntech.cabosse.supplier.dto.SupplierResponseDto;
import com.ntech.cabosse.supplier.dto.SupplierUpsertDto;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
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

import static com.ntech.cabosse.shared.imports.ImportParsers.listSink;
import static com.ntech.cabosse.shared.imports.ImportParsers.slugify;
import static com.ntech.cabosse.shared.imports.ImportParsers.trimOrNull;

@ApplicationScoped
public class SupplierImportService {

    @Inject SupplierRepository suppliers;
    @Inject SupplierService supplierService;

    public SupplierImportPreviewDto preview(List<SupplierImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new SupplierImportPreviewDto(0, 0, 0, 0, List.of());
        }

        Set<String> existingCodes = new HashSet<>();
        for (SupplierEntity e : suppliers.listAll()) {
            if (e.code != null) existingCodes.add(e.code.toLowerCase(Locale.ROOT));
        }
        Map<String, Integer> firstByCode = new HashMap<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (SupplierImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            ImportParsers.IssueSink sink = listSink(issues, FieldIssue::new);

            String name = trimOrNull(raw.name());
            if (name == null) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-required")));
            else if (name.length() < 2) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-too-short")));
            else if (name.length() > 120) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-too-long")));

            String code = trimOrNull(raw.code());
            if (code != null && !code.matches("[a-z0-9-]{2,40}")) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-supplier-code-invalid")));
            }
            String resolvedCode = (code != null) ? code : (name != null ? slugify(name) : null);

            String email = trimOrNull(raw.email());
            if (email != null && !email.matches("^.+@.+\\..+$")) {
                issues.add(new FieldIssue("email", Messages.msg("m.imp-email-invalid")));
            }
            String phone = trimOrNull(raw.phone());
            if (phone != null && !phone.matches("\\+?[\\d\\s()-]{6,25}")) {
                issues.add(new FieldIssue("phone", Messages.msg("m.imp-phone-invalid")));
            }
            String countryCode = trimOrNull(raw.countryCode());
            if (countryCode != null && countryCode.length() > 2) {
                issues.add(new FieldIssue("countryCode", Messages.msg("m.imp-country-code-too-long")));
            }

            String legalName = trimOrNull(raw.legalName());
            String taxNumber = trimOrNull(raw.taxNumber());
            String addressLine = trimOrNull(raw.addressLine());
            String cityName = trimOrNull(raw.cityName());
            String contactName = trimOrNull(raw.contactName());
            String paymentTerms = trimOrNull(raw.paymentTerms());
            String notes = trimOrNull(raw.notes());

            // (sink is unused in this entity for now — kept for symmetry with other importers)
            @SuppressWarnings("unused") ImportParsers.IssueSink _sink = sink;

            Status status;
            if (!issues.isEmpty()) {
                status = Status.INVALID;
                invalid++;
            } else if (resolvedCode != null && existingCodes.contains(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-code-already-used", resolvedCode)));
                status = Status.DUPLICATE_IN_DB;
                duplicate++;
            } else if (resolvedCode != null && firstByCode.containsKey(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-code-duplicate-at-line", resolvedCode,
                        String.valueOf(firstByCode.get(resolvedCode.toLowerCase(Locale.ROOT))))));
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else {
                if (resolvedCode != null) firstByCode.put(resolvedCode.toLowerCase(Locale.ROOT), raw.rowNumber());
                status = Status.READY;
                ready++;
            }

            Normalized normalized = new Normalized(
                    resolvedCode, name, legalName, taxNumber,
                    email, phone, addressLine, cityName, countryCode,
                    contactName, paymentTerms, notes
            );

            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        return new SupplierImportPreviewDto(input.size(), ready, invalid, duplicate, rows);
    }

    public SupplierImportCommitResponseDto commit(List<SupplierImportRowDto> input) {
        SupplierImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                SupplierUpsertDto payload = new SupplierUpsertDto(
                        n.code(), n.name(), n.legalName(), n.taxNumber(),
                        n.email(), n.phone(), n.addressLine(), n.cityName(), n.countryCode(),
                        n.contactName(), n.paymentTerms(), n.notes(), null, null, null, null, null
                );
                SupplierResponseDto created = supplierService.create(payload);
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(), issues));
            }
        }

        return new SupplierImportCommitResponseDto(
                preview.totalRows(),
                createdIds.size(),
                skipped.size(),
                createdIds,
                skipped
        );
    }
}
