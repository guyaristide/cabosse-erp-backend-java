package com.ntech.cabosse.customer.service;

import com.ntech.cabosse.customer.dto.CustomerImportCommitResponseDto;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto.FieldIssue;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto.Normalized;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto.Row;
import com.ntech.cabosse.customer.dto.CustomerImportPreviewDto.Status;
import com.ntech.cabosse.customer.dto.CustomerImportRowDto;
import com.ntech.cabosse.customer.dto.CustomerResponseDto;
import com.ntech.cabosse.customer.dto.CustomerUpsertDto;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportParsers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ntech.cabosse.shared.imports.ImportParsers.listSink;
import static com.ntech.cabosse.shared.imports.ImportParsers.normalize;
import static com.ntech.cabosse.shared.imports.ImportParsers.parseDecimal;
import static com.ntech.cabosse.shared.imports.ImportParsers.slugify;
import static com.ntech.cabosse.shared.imports.ImportParsers.trimOrNull;

@ApplicationScoped
public class CustomerImportService {

    @Inject CustomerRepository customers;
    @Inject CustomerService customerService;

    public CustomerImportPreviewDto preview(List<CustomerImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new CustomerImportPreviewDto(0, 0, 0, 0, List.of());
        }

        Set<String> existingCodes = new HashSet<>();
        for (CustomerEntity e : customers.listAll()) {
            if (e.code != null) existingCodes.add(e.code.toLowerCase(Locale.ROOT));
        }
        Map<String, Integer> firstByCode = new HashMap<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (CustomerImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            ImportParsers.IssueSink sink = listSink(issues, FieldIssue::new);

            String name = trimOrNull(raw.name());
            if (name == null) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-required")));
            else if (name.length() < 2) issues.add(new FieldIssue("name", Messages.msg("m.imp-name-short")));

            String type = parseCustomerType(raw.type(), issues);

            String code = trimOrNull(raw.code());
            if (code != null && !code.matches("[a-z0-9-]{2,40}")) {
                issues.add(new FieldIssue("code", Messages.msg("m.imp-customer-code-invalid")));
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
                issues.add(new FieldIssue("countryCode", Messages.msg("m.imp-country-code-iso2")));
            }
            BigDecimal creditLimit = parseDecimal(raw.creditLimit(), sink, "creditLimit");
            if (creditLimit != null && creditLimit.signum() < 0) {
                issues.add(new FieldIssue("creditLimit", Messages.msg("m.imp-credit-limit-negative")));
            }

            String legalName = trimOrNull(raw.legalName());
            String taxNumber = trimOrNull(raw.taxNumber());
            String addressLine = trimOrNull(raw.addressLine());
            String cityName = trimOrNull(raw.cityName());
            String contactName = trimOrNull(raw.contactName());
            String notes = trimOrNull(raw.notes());

            Status status;
            if (!issues.isEmpty()) {
                status = Status.INVALID; invalid++;
            } else if (resolvedCode != null && existingCodes.contains(resolvedCode.toLowerCase(Locale.ROOT))) {
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

            Normalized normalized = new Normalized(
                    resolvedCode, name, type, legalName, taxNumber,
                    email, phone, addressLine, cityName, countryCode,
                    contactName, creditLimit, notes
            );

            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        return new CustomerImportPreviewDto(input.size(), ready, invalid, duplicate, rows);
    }

    public CustomerImportCommitResponseDto commit(List<CustomerImportRowDto> input) {
        CustomerImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();

        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) {
                skipped.add(row); continue;
            }
            Normalized n = row.normalized();
            try {
                CustomerUpsertDto payload = new CustomerUpsertDto(
                        n.code(), n.name(), n.type(),
                        null, // canal non géré dans l'import legacy — renseigné via l'écran d'édition
                        n.legalName(), n.taxNumber(),
                        n.email(), n.phone(), n.addressLine(), n.cityName(), n.countryCode(),
                        n.contactName(), n.creditLimit(), n.notes()
                );
                CustomerResponseDto created = customerService.create(payload);
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(), issues));
            }
        }
        return new CustomerImportCommitResponseDto(
                preview.totalRows(), createdIds.size(), skipped.size(), createdIds, skipped
        );
    }

    private static String parseCustomerType(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(new FieldIssue("type", Messages.msg("m.imp-customer-type-required")));
            return null;
        }
        String n = normalize(raw);
        return switch (n) {
            case "individual", "particulier", "person" -> "INDIVIDUAL";
            case "company", "entreprise", "societe", "société" -> "COMPANY";
            default -> {
                issues.add(new FieldIssue("type", Messages.msg("m.imp-customer-type-unknown", raw)));
                yield null;
            }
        };
    }
}
