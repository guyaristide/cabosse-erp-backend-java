package com.ntech.cabosse.agriculture.parcel.service;

import com.ntech.cabosse.agriculture.parcel.dto.ParcelCampaignYieldDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportCommitResponseDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto.FieldIssue;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto.Normalized;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto.Row;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportPreviewDto.Status;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelImportRowDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelUpsertDto;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelEntity;
import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.crop.entity.CropEntity;
import com.ntech.cabosse.crop.repository.CropRepository;
import com.ntech.cabosse.department.entity.DepartmentEntity;
import com.ntech.cabosse.department.repository.DepartmentRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.service.ProducerLookup;
import com.ntech.cabosse.region.entity.RegionEntity;
import com.ntech.cabosse.region.repository.RegionRepository;
import com.ntech.cabosse.shared.imports.FuzzyLabels;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import de parcelles depuis un fichier.
 *
 * <p>Trois différences avec l'import des producteurs, dictées par la nature
 * de la donnée.</p>
 *
 * <p><strong>Le contour n'est pas importable.</strong> Une liste de sommets
 * ne tient pas dans une cellule et personne ne la saisit ainsi. L'import
 * pose le point central, qui suffit à la conformité des petites surfaces ;
 * le contour se trace ensuite sur la carte.</p>
 *
 * <p><strong>Les coordonnées arrivent dans tous les formats.</strong> Degré
 * décimal à virgule ou à point, degrés minutes secondes avec orientation :
 * les relevés terrain mélangent tout. On normalise, et on refuse ce qui
 * sort des bornes plutôt que de poser une parcelle en pleine mer.</p>
 *
 * <p><strong>Une parcelle sans producteur est un avertissement.</strong>
 * Elle reste créable, une structure exploitant en propre existe, mais elle
 * sort des projections par producteur : l'utilisateur doit le décider.</p>
 */
@ApplicationScoped
public class ParcelImportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    );

    /** 5°32'46.3"N, 5° 32' 46.3" N, 5 32 46.3 N. */
    private static final Pattern DMS = Pattern.compile(
            "^\\s*(\\d{1,3})\\s*[°:\\s]\\s*(\\d{1,2})\\s*['′:\\s]\\s*([\\d.,]+)\\s*[\"″]?\\s*([NSEWO])?\\s*$",
            Pattern.CASE_INSENSITIVE);

    @Inject ParcelRepository parcels;
    @Inject ParcelService parcelService;
    @Inject MemberRepository members;
    @Inject ProducerLookup producerLookup;
    @Inject CropRepository crops;
    @Inject RegionRepository regions;
    @Inject DepartmentRepository departments;
    @Inject CampaignResolver campaignResolver;
    @Inject IdGenerator idGenerator;

    // ─── Aperçu ─────────────────────────────────────────────────────

    public ParcelImportPreviewDto preview(List<ParcelImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new ParcelImportPreviewDto(0, 0, 0, 0, 0, 0, List.of());
        }

        List<ParcelEntity> existing = parcels.listAll();
        List<MemberEntity> allMembers = members.listAll();
        ProducerLookup.Index producers = producerLookup.index();
        List<String> knownCrops = crops.listAll().stream().map(c -> c.name).toList();
        List<String> knownRegions = regions.listAll().stream().map(r -> r.name).toList();
        List<String> knownDepartments = departments.listAll().stream().map(d -> d.name).toList();
        Set<String> codesSeen = new HashSet<>();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, update = 0, warning = 0, invalid = 0, duplicate = 0;

        for (ParcelImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            String name = trim(raw.name());
            if (name == null) issues.add(new FieldIssue("name", "Nom de parcelle requis."));

            String code = trim(raw.code());
            BigDecimal surface = parseDecimal(raw.surfaceHa(), "surfaceHa", issues);
            if (surface != null && surface.signum() <= 0) {
                issues.add(new FieldIssue("surfaceHa", "Superficie nulle ou négative."));
            }

            Double latitude = parseCoordinate(raw.latitude(), "latitude", issues);
            Double longitude = parseCoordinate(raw.longitude(), "longitude", issues);
            if (latitude != null && (latitude < -90 || latitude > 90)) {
                issues.add(new FieldIssue("latitude", "Latitude hors [-90, 90]."));
                latitude = null;
            }
            if (longitude != null && (longitude < -180 || longitude > 180)) {
                issues.add(new FieldIssue("longitude", "Longitude hors [-180, 180]."));
                longitude = null;
            }
            if ((latitude == null) != (longitude == null)) {
                issues.add(new FieldIssue("latitude",
                        "Latitude et longitude vont par paire : renseignez les deux ou aucune."));
            }

            LocalDate plantingDate = parseDate(raw.plantingDate(), "plantingDate", issues);
            Integer plantingYear = parseInt(raw.plantingYear(), "plantingYear", issues);
            BigDecimal estimate = parseDecimal(raw.estimateKg(), "estimateKg", issues);
            BigDecimal yieldPerHa = parseDecimal(raw.yieldPerHa(), "yieldPerHa", issues);

            String cropName = resolveLabel(raw.crop(), knownCrops);
            String regionName = resolveLabel(raw.region(), knownRegions);
            String departmentName = resolveLabel(raw.department(), knownDepartments);
            String status = parseStatus(raw.status(), issues);

            MemberEntity member = findMember(producers, allMembers, raw.producerCode(), raw.producerName());
            boolean producerRequested = trim(raw.producerCode()) != null || trim(raw.producerName()) != null;

            Normalized normalized = new Normalized(
                    code, name,
                    member != null ? member.id : null,
                    member != null ? member.name : trim(raw.producerName()),
                    surface, latitude, longitude,
                    cropName, parseBoolean(raw.mainCrop()), trim(raw.variety()),
                    plantingDate != null ? plantingDate.format(ISO) : null, plantingYear,
                    regionName, departmentName,
                    status, estimate, yieldPerHa, trim(raw.notes()));

            ParcelEntity match = findExisting(existing, code, name, member);
            UUID matchedId = match != null ? match.id : null;
            String matchedOn = match != null
                    ? (code != null && code.equalsIgnoreCase(match.code) ? "Code parcelle" : "Nom et producteur")
                    : null;

            Status rowStatus;
            String dedupKey = code != null
                    ? "code:" + code.toLowerCase(Locale.ROOT)
                    : (name != null && member != null
                            ? "nom:" + FuzzyLabels.canonical(name) + "|" + member.id : null);

            if (!issues.isEmpty()) {
                rowStatus = Status.INVALID;
                invalid++;
            } else if (dedupKey != null && !codesSeen.add(dedupKey)) {
                issues.add(new FieldIssue("code", "Parcelle déjà présente plus haut dans le fichier."));
                rowStatus = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else if (producerRequested && member == null) {
                String wanted = trim(raw.producerCode()) != null
                        ? "« " + trim(raw.producerCode()) + " »"
                        : "« " + trim(raw.producerName()) + " »";
                issues.add(new FieldIssue("producerCode",
                        "Producteur " + wanted + " absent du registre (recherche par numéro puis "
                                + "par nom). Vérifiez le code producteur ou enrôlez le membre avant "
                                + "l'import ; sinon la parcelle sera créée sans rattachement et "
                                + "sortira des projections par producteur."));
                rowStatus = Status.WARNING;
                warning++;
            } else if (match != null) {
                rowStatus = Status.UPDATE;
                update++;
            } else {
                rowStatus = Status.READY;
                ready++;
            }

            rows.add(new Row(raw.rowNumber(), rowStatus, normalized, matchedId, matchedOn, issues));
        }

        return new ParcelImportPreviewDto(input.size(), ready, update, warning, invalid, duplicate, rows);
    }

    // ─── Application ────────────────────────────────────────────────

    /**
     * @param campaignId      campagne à laquelle rattacher les estimations du
     *                        fichier ; choisie une fois pour tout l'import,
     *                        car un fichier de recensement porte une saison
     * @param includeWarnings crée les parcelles dont le producteur n'a pas
     *                        été retrouvé, sans rattachement
     */
    public ParcelImportCommitResponseDto commit(List<ParcelImportRowDto> input,
                                                UUID campaignId, boolean includeWarnings) {
        ParcelImportPreviewDto preview = preview(input);
        Map<Integer, ParcelImportRowDto> rawByRow = new HashMap<>();
        for (ParcelImportRowDto r : input) rawByRow.put(r.rowNumber(), r);
        List<UUID> created = new ArrayList<>();
        List<UUID> updated = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        LinkedHashSet<String> createdCrops = new LinkedHashSet<>();
        LinkedHashSet<String> createdRegions = new LinkedHashSet<>();
        LinkedHashSet<String> createdDepartments = new LinkedHashSet<>();
        int orphans = 0;

        CampaignEntity campaign = null;
        boolean needsCampaign = preview.rows().stream()
                .anyMatch(r -> r.normalized() != null && r.normalized().estimateKg() != null);
        if (needsCampaign) {
            campaign = campaignResolver.resolveOptional(campaignId);
        }

        for (Row row : preview.rows()) {
            boolean applicable = row.status() == Status.READY
                    || row.status() == Status.UPDATE
                    || (row.status() == Status.WARNING && includeWarnings);
            if (!applicable || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            Normalized n = row.normalized();
            try {
                String cropCode = resolveCrop(n.cropName(), createdCrops);
                String regionCode = resolveRegion(n.regionName(), createdRegions);
                String departmentCode = resolveDepartment(n.departmentName(), createdDepartments);
                if (row.status() == Status.WARNING) orphans++;

                if (row.matchedParcelId() != null) {
                    ParcelUpsertDto payload = mergedPayload(row.matchedParcelId(), n,
                            rawByRow.get(row.rowNumber()), cropCode, regionCode,
                            departmentCode, campaign);
                    ParcelResponseDto dto = parcelService.update(row.matchedParcelId(), payload);
                    updated.add(dto.id());
                } else {
                    List<ParcelCampaignYieldDto> yields = new ArrayList<>();
                    if (n.estimateKg() != null && campaign != null) {
                        yields.add(new ParcelCampaignYieldDto(
                                campaign.id, n.yieldPerHa(), n.estimateKg()));
                    }
                    ParcelUpsertDto payload = new ParcelUpsertDto(
                            n.code(), n.name(), n.surfaceHa(),
                            // Contour non importable : seul le point central entre.
                            null,
                            n.latitude() != null && n.longitude() != null
                                    ? List.of(n.longitude(), n.latitude())
                                    : null,
                            n.variety(), cropCode, n.mainCrop(),
                            n.plantingDate() != null ? LocalDate.parse(n.plantingDate()) : null,
                            n.plantingYear(),
                            regionCode, departmentCode,
                            List.of(), n.memberId(), yields,
                            ParcelStatus.valueOf(n.status() != null ? n.status() : "ACTIVE"),
                            n.notes());
                    ParcelResponseDto dto = parcelService.create(payload);
                    created.add(dto.id());
                }
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(),
                        row.matchedParcelId(), row.matchedOn(), issues));
            }
        }

        return new ParcelImportCommitResponseDto(
                preview.totalRows(), created.size(), updated.size(), skipped.size(),
                created, updated,
                List.copyOf(createdCrops), List.copyOf(createdRegions), List.copyOf(createdDepartments),
                orphans, skipped);
    }

    /**
     * Ligne UPDATE : fusion, pas remplacement (décision du 26/08/2026).
     * L'import ne touche qu'aux champs présents dans le fichier ; le contour
     * tracé sur la carte, les certifications et les estimations des autres
     * campagnes sont préservés. Corollaire : un import ne peut pas vider un
     * champ, cela se fait à l'écran.
     */
    private ParcelUpsertDto mergedPayload(UUID parcelId, Normalized n, ParcelImportRowDto raw,
                                          String cropCode, String regionCode,
                                          String departmentCode, CampaignEntity campaign) {
        ParcelResponseDto cur = parcelService.getById(parcelId);

        List<ParcelCampaignYieldDto> yields = new ArrayList<>(cur.campaignYields());
        if (n.estimateKg() != null && campaign != null) {
            UUID cid = campaign.id;
            yields.removeIf(y -> cid.equals(y.campaignId()));
            yields.add(new ParcelCampaignYieldDto(cid, n.yieldPerHa(), n.estimateKg()));
        }

        // Normalized ne distingue pas « absent » de la valeur par défaut :
        // la présence se lit sur la ligne brute.
        boolean statusProvided = raw != null && trim(raw.status()) != null;
        boolean mainCropProvided = raw != null && trim(raw.mainCrop()) != null;

        return new ParcelUpsertDto(
                cur.code() != null ? cur.code() : n.code(),
                n.name(),
                n.surfaceHa() != null ? n.surfaceHa() : cur.surfaceHa(),
                cur.gpsPolygonCoordinates(),
                n.latitude() != null && n.longitude() != null
                        ? List.of(n.longitude(), n.latitude())
                        : cur.gpsCenter(),
                n.variety() != null ? n.variety() : cur.variety(),
                cropCode != null ? cropCode : cur.cropCode(),
                mainCropProvided ? n.mainCrop() : cur.mainCrop(),
                n.plantingDate() != null ? LocalDate.parse(n.plantingDate()) : cur.plantingDate(),
                n.plantingYear() != null ? n.plantingYear() : cur.plantingYear(),
                regionCode != null ? regionCode : cur.regionCode(),
                departmentCode != null ? departmentCode : cur.departmentCode(),
                cur.certifications(),
                n.memberId() != null ? n.memberId() : cur.memberId(),
                yields,
                statusProvided && n.status() != null
                        ? ParcelStatus.valueOf(n.status()) : cur.status(),
                n.notes() != null ? n.notes() : cur.notes());
    }

    // ─── Rapprochements ─────────────────────────────────────────────

    /**
     * Producteur par numéro, puis par nom complet. Le numéro passe par le
     * rapprochement partagé, qui distingue code interne et carte et refuse
     * un numéro porté par deux producteurs.
     */
    private static MemberEntity findMember(ProducerLookup.Index producers, List<MemberEntity> all,
                                           String rawCode, String rawName) {
        String code = trim(rawCode);
        if (code != null) {
            ProducerLookup.Match match = producers.resolve(code, null);
            if (match.found()) return match.member();
        }
        String name = trim(rawName);
        if (name != null) {
            String canonical = FuzzyLabels.canonical(name);
            return all.stream()
                    .filter(m -> FuzzyLabels.canonical(m.name).equals(canonical))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /** Parcelle par code, sinon par nom au sein du même producteur. */
    private static ParcelEntity findExisting(List<ParcelEntity> existing, String code,
                                             String name, MemberEntity member) {
        if (code != null) {
            Optional<ParcelEntity> byCode = existing.stream()
                    .filter(p -> code.equalsIgnoreCase(p.code))
                    .findFirst();
            if (byCode.isPresent()) return byCode.get();
        }
        if (name != null && member != null) {
            String canonical = FuzzyLabels.canonical(name);
            return existing.stream()
                    .filter(p -> member.id.equals(p.memberId)
                            && FuzzyLabels.canonical(p.name).equals(canonical))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /** Rapproche un libellé du référentiel existant, sinon le garde tel quel. */
    private static String resolveLabel(String raw, List<String> known) {
        String value = trim(raw);
        if (value == null) return null;
        String matched = FuzzyLabels.bestMatch(value, known);
        return matched != null ? matched : value;
    }

    // ─── Référentiels créés à la volée ──────────────────────────────

    private String resolveCrop(String name, LinkedHashSet<String> createdCrops) {
        if (name == null) return null;
        for (CropEntity c : crops.listAll()) {
            if (FuzzyLabels.matches(c.name, name) || FuzzyLabels.matches(c.code, name)) return c.code;
        }
        CropEntity created = new CropEntity();
        created.id = idGenerator.newId();
        created.code = slug(name);
        created.name = name.trim();
        created.active = true;
        created.createdAt = Instant.now();
        created.updatedAt = created.createdAt;
        crops.insert(created);
        createdCrops.add(created.name);
        return created.code;
    }

    private String resolveRegion(String name, LinkedHashSet<String> createdRegions) {
        if (name == null) return null;
        for (RegionEntity r : regions.listAll()) {
            if (FuzzyLabels.matches(r.name, name) || FuzzyLabels.matches(r.code, name)) return r.code;
        }
        RegionEntity created = new RegionEntity();
        created.id = idGenerator.newId();
        created.code = slug(name).toUpperCase(Locale.ROOT);
        created.name = name.trim();
        created.active = true;
        created.createdAt = Instant.now();
        created.updatedAt = created.createdAt;
        regions.insert(created);
        createdRegions.add(created.name);
        return created.code;
    }

    private String resolveDepartment(String name, LinkedHashSet<String> createdDepartments) {
        if (name == null) return null;
        for (DepartmentEntity d : departments.listAll()) {
            if (FuzzyLabels.matches(d.name, name) || FuzzyLabels.matches(d.code, name)) return d.code;
        }
        DepartmentEntity created = new DepartmentEntity();
        created.id = idGenerator.newId();
        created.code = slug(name).toUpperCase(Locale.ROOT);
        created.name = name.trim();
        created.active = true;
        created.createdAt = Instant.now();
        created.updatedAt = created.createdAt;
        departments.insert(created);
        createdDepartments.add(created.name);
        return created.code;
    }

    // ─── Conversions ────────────────────────────────────────────────

    /**
     * Degré décimal ou degrés minutes secondes. Les relevés terrain
     * mélangent les deux, et la virgule décimale française cohabite avec le
     * point : refuser l'un des deux reviendrait à refuser la moitié des
     * fichiers.
     */
    // Publique pour être testée seule : c'est la conversion la plus
    // exposée aux formats de terrain, elle mérite ses propres cas.
    public static Double parseCoordinate(String raw, String field, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;

        Matcher dms = DMS.matcher(value);
        if (dms.matches()) {
            double degrees = Double.parseDouble(dms.group(1));
            double minutes = Double.parseDouble(dms.group(2));
            double seconds = Double.parseDouble(dms.group(3).replace(',', '.'));
            double decimal = degrees + minutes / 60 + seconds / 3600;
            String orientation = dms.group(4);
            if (orientation != null) {
                String o = orientation.toUpperCase(Locale.ROOT);
                if (o.equals("S") || o.equals("W") || o.equals("O")) decimal = -decimal;
            }
            return decimal;
        }

        try {
            return Double.valueOf(value.replace(',', '.').replaceAll("[\\s ]", ""));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, "Coordonnée « " + raw + " » illisible."));
            return null;
        }
    }

    private static String parseStatus(String raw, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return "ACTIVE";
        String c = FuzzyLabels.canonical(value);
        if (c.contains("production") || c.contains("active")) return "ACTIVE";
        if (c.contains("jachere")) return "FALLOW";
        if (c.contains("replant")) return "REPLANTING";
        if (c.contains("abandon")) return "ABANDONED";
        issues.add(new FieldIssue("status",
                "Statut « " + raw + " » non reconnu (en production, jachère, replantation, abandonnée)."));
        return null;
    }

    private static boolean parseBoolean(String raw) {
        String value = trim(raw);
        if (value == null) return false;
        String c = FuzzyLabels.canonical(value);
        return c.startsWith("oui") || c.equals("o") || c.equals("x") || c.equals("1")
                || c.startsWith("yes") || c.startsWith("vrai") || c.startsWith("principale");
    }

    private static LocalDate parseDate(String raw, String field, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (RuntimeException ignored) {
                // format suivant
            }
        }
        // Une année seule est une date de plantation acceptable.
        if (value.matches("\\d{4}")) return null;
        issues.add(new FieldIssue(field, "Date « " + raw + " » illisible (attendu JJ/MM/AAAA)."));
        return null;
    }

    private static Integer parseInt(String raw, String field, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.replaceAll("[\\s ]", ""));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, "Nombre « " + raw + " » illisible."));
            return null;
        }
    }

    private static BigDecimal parseDecimal(String raw, String field, List<FieldIssue> issues) {
        String value = trim(raw);
        if (value == null) return null;
        try {
            return new BigDecimal(value.replaceAll("[\\s ]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, "Nombre « " + raw + " » illisible."));
            return null;
        }
    }

    private static String slug(String name) {
        String s = FuzzyLabels.canonical(name).replace(' ', '-');
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
