package com.ntech.cabosse.site.service;

import com.ntech.cabosse.site.dto.SiteImportCommitResponseDto;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto.FieldIssue;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto.Normalized;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto.Row;
import com.ntech.cabosse.site.dto.SiteImportPreviewDto.Status;
import com.ntech.cabosse.site.dto.SiteImportRowDto;
import com.ntech.cabosse.site.dto.SiteResponseDto;
import com.ntech.cabosse.site.dto.SiteUpsertDto;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.repository.SiteRepository;
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
public class SiteImportService {

    @Inject SiteRepository sites;
    @Inject SiteService siteService;

    public SiteImportPreviewDto preview(List<SiteImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new SiteImportPreviewDto(0, 0, 0, 0, List.of());
        }
        Set<String> existingCodes = new HashSet<>();
        for (SiteEntity e : sites.listAll()) {
            if (e.code != null) existingCodes.add(e.code.toLowerCase(Locale.ROOT));
        }
        Map<String, Integer> firstByCode = new HashMap<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, invalid = 0, duplicate = 0;

        for (SiteImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            var sink = listSink(issues, FieldIssue::new);

            String type = parseSiteType(raw.type(), issues);

            String name = trimOrNull(raw.name());
            if (name == null) issues.add(new FieldIssue("name", "Nom requis."));

            String code = trimOrNull(raw.code());
            if (code != null && !code.matches("[a-z0-9-]{2,40}")) {
                issues.add(new FieldIssue("code", "Code invalide : minuscules/chiffres/tirets, 2-40."));
            }
            String resolvedCode = (code != null) ? code : (name != null ? slugify(name) : null);

            BigDecimal lat = parseDecimal(raw.latitude(), sink, "latitude");
            BigDecimal lng = parseDecimal(raw.longitude(), sink, "longitude");
            Double latitude = lat != null ? lat.doubleValue() : null;
            Double longitude = lng != null ? lng.doubleValue() : null;
            if (latitude != null && (latitude < -90 || latitude > 90)) {
                issues.add(new FieldIssue("latitude", "Latitude hors [-90, 90]."));
            }
            if (longitude != null && (longitude < -180 || longitude > 180)) {
                issues.add(new FieldIssue("longitude", "Longitude hors [-180, 180]."));
            }

            String email = trimOrNull(raw.email());
            if (email != null && !email.matches("^.+@.+\\..+$")) {
                issues.add(new FieldIssue("email", "Adresse e-mail invalide."));
            }
            String phone = trimOrNull(raw.phone());
            if (phone != null && !phone.matches("\\+?[\\d\\s()-]{6,25}")) {
                issues.add(new FieldIssue("phone", "Téléphone invalide."));
            }
            String countryCode = trimOrNull(raw.countryCode());
            if (countryCode != null && countryCode.length() > 2) {
                issues.add(new FieldIssue("countryCode", "Code pays ISO 2 lettres."));
            }
            String regionCode = trimOrNull(raw.regionCode());
            String addressLine = trimOrNull(raw.addressLine());
            String cityName = trimOrNull(raw.cityName());
            String managerName = trimOrNull(raw.managerName());
            String openingHours = trimOrNull(raw.openingHours());
            String description = trimOrNull(raw.description());

            Status status;
            if (!issues.isEmpty()) { status = Status.INVALID; invalid++; }
            else if (resolvedCode != null && existingCodes.contains(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", "Code « " + resolvedCode + " » déjà utilisé."));
                status = Status.DUPLICATE_IN_DB; duplicate++;
            } else if (resolvedCode != null && firstByCode.containsKey(resolvedCode.toLowerCase(Locale.ROOT))) {
                issues.add(new FieldIssue("code", "Code « " + resolvedCode
                        + " » déjà ligne " + firstByCode.get(resolvedCode.toLowerCase(Locale.ROOT)) + "."));
                status = Status.DUPLICATE_IN_FILE; duplicate++;
            } else {
                if (resolvedCode != null) firstByCode.put(resolvedCode.toLowerCase(Locale.ROOT), raw.rowNumber());
                status = Status.READY; ready++;
            }

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(type, resolvedCode, name,
                            addressLine, cityName, regionCode, countryCode,
                            latitude, longitude,
                            phone, email, managerName, openingHours, description),
                    issues));
        }
        return new SiteImportPreviewDto(input.size(), ready, invalid, duplicate, rows);
    }

    public SiteImportCommitResponseDto commit(List<SiteImportRowDto> input) {
        SiteImportPreviewDto preview = preview(input);
        List<UUID> createdIds = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) { skipped.add(row); continue; }
            Normalized n = row.normalized();
            try {
                SiteUpsertDto payload = new SiteUpsertDto(
                        n.type(), n.code(), n.name(),
                        n.addressLine(), /* cityId */ null, n.cityName(), n.regionCode(), n.countryCode(),
                        n.latitude(), n.longitude(),
                        n.phone(), n.email(), n.managerName(), n.description(), n.openingHours()
                );
                SiteResponseDto created = siteService.create(payload);
                createdIds.add(created.id());
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(), issues));
            }
        }
        return new SiteImportCommitResponseDto(
                preview.totalRows(), createdIds.size(), skipped.size(), createdIds, skipped
        );
    }

    private static String parseSiteType(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) {
            issues.add(new FieldIssue("type", "Type requis (Transformation ou Point de vente)."));
            return null;
        }
        String n = normalize(raw);
        return switch (n) {
            case "transformation" -> "TRANSFORMATION";
            case "sales point", "point de vente", "pointdevente", "boutique" -> "SALES_POINT";
            default -> {
                issues.add(new FieldIssue("type",
                        "Type « " + raw + " » non reconnu (Transformation ou Point de vente)."));
                yield null;
            }
        };
    }
}
