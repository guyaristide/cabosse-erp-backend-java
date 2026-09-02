package com.ntech.cabosse.members.service;

import com.ntech.cabosse.agriculture.parcel.entity.ParcelStatus;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelResponseDto;
import com.ntech.cabosse.agriculture.parcel.dto.ParcelUpsertDto;
import com.ntech.cabosse.collector.entity.SectionEntity;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.iddocument.entity.IdDocumentTypeEntity;
import com.ntech.cabosse.iddocument.repository.IdDocumentTypeRepository;
import com.ntech.cabosse.iddocument.service.IdDocumentTypeCanonical;
import com.ntech.cabosse.members.dto.MemberEnrolmentDto;
import com.ntech.cabosse.members.dto.MemberExternalCodeDto;
import com.ntech.cabosse.members.dto.MemberHouseholdDto;
import com.ntech.cabosse.members.dto.MemberIdentityDocumentDto;
import com.ntech.cabosse.members.dto.MemberImportCommitResponseDto;
import com.ntech.cabosse.members.dto.MemberLegalIdentityDto;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.FieldIssue;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.LocalityMatch;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.LocalityMatchStatus;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.Normalized;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.Row;
import com.ntech.cabosse.members.dto.MemberImportPreviewDto.Status;
import com.ntech.cabosse.members.dto.MemberImportRowDto;
import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberExternalCode;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.i18n.Messages;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Import de membres-producteurs depuis un fichier.
 *
 * <p>Trois partis pris, tirés du terrain.</p>
 *
 * <p><strong>Rapprochement plutôt que création aveugle.</strong> Un fichier
 * corrigé est réimporté plusieurs fois. Chaque ligne cherche donc un membre
 * existant, par code interne, puis par code producteur externe, puis par
 * téléphone. Le critère retenu est affiché pour que l'utilisateur puisse le
 * contester avant d'appliquer.</p>
 *
 * <p><strong>Référentiels créés à la volée, mais normalisés.</strong>
 * Sections et types de pièce absents sont créés, après rapprochement
 * tolérant : « carte nationnale d'identite » rejoint « Carte nationale
 * d'identité » au lieu d'ouvrir une entrée de plus.</p>
 *
 * <p><strong>Incohérence de ménage : écartée, pas bloquante.</strong> Un
 * total d'enfants qui ne tombe pas juste sort la ligne de l'import par
 * défaut, mais l'utilisateur peut passer outre en connaissance de cause.</p>
 */
@ApplicationScoped
public class MemberImportService {

    /**
     * Toutes les espaces, y compris les insécables.
     *
     * <p>{@code \s} ne couvre que l'espace ordinaire. Or un tableur
     * sépare les milliers par une espace fine insécable (U+202F) ou
     * insécable (U+00A0) : un fichier exporté puis redéposé revenait
     * alors avec « 2 003 » jugé illisible, alors que c'est nous qui
     * l'avions écrit ainsi. {@code \p{Zs}} les prend toutes.</p>
     */
    private static final String BLANKS = "[\\s\\p{Zs}]";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    );

    @Inject MemberRepository members;
    @Inject com.ntech.cabosse.plan.service.PlanLimitService planLimits;
    @Inject MemberService memberService;
    @Inject SectionRepository sections;
    @Inject com.ntech.cabosse.locality.repository.LocalityRepository localities;
    @Inject com.ntech.cabosse.locality.service.LocalityService localityService;
    @Inject IdDocumentTypeRepository idDocumentTypes;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.agriculture.parcel.repository.ParcelRepository parcels;
    @Inject com.ntech.cabosse.agriculture.parcel.service.ParcelService parcelService;

    // ─── Aperçu ─────────────────────────────────────────────────────

    public MemberImportPreviewDto preview(List<MemberImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new MemberImportPreviewDto(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        List<MemberEntity> existing = members.listAll();
        Map<Integer, String> keysSeen = new HashMap<>();
        List<String> knownSections = sections.listAll().stream().map(s -> s.name).toList();
        // Les parcelles déjà connues, pour reconnaître un code au réimport :
        // sans cela, chaque passage doublerait la superficie du tenant.
        Map<String, UUID> knownParcels = new HashMap<>();
        for (var p : parcels.listAll()) {
            if (p.code != null && !p.code.isBlank()) knownParcels.put(p.code.trim().toUpperCase(Locale.ROOT), p.id);
        }
        List<String> knownDocTypes = idDocumentTypes.listAll().stream().map(t -> t.name).toList();
        // Le référentiel des villages, chargé une fois : le rapprochement se
        // fait ligne à ligne, sur des centaines de lignes.
        List<com.ntech.cabosse.locality.entity.LocalityEntity> knownLocalities = localities.listAll();
        Map<UUID, String> sectionNameById = sections.listAll().stream()
                .collect(java.util.stream.Collectors.toMap(sec -> sec.id, sec -> sec.name, (a, b) -> a));

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, update = 0, warning = 0, invalid = 0, duplicate = 0;
        int additionalParcel = 0, parcelsToCreate = 0, parcelsToUpdate = 0;
        int parcelsWithoutPosition = 0;

        // Rang de la parcelle chez son producteur, pour nommer celles que
        // le registre ne nomme pas. Un producteur déclare ses plantations
        // sur plusieurs lignes : le compteur suit le fichier, pas la ligne.
        Map<String, Integer> parcelRanks = new java.util.HashMap<>();

        for (MemberImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            String lastName = trim(raw.lastName());
            String firstName = trim(raw.firstName());
            if (lastName == null && firstName == null) {
                issues.add(new FieldIssue("lastName", Messages.msg("m.imp-name-required")));
            }

            String gender = parseGender(raw.gender(), issues);
            String personType = parsePersonType(raw.personType());
            String maritalStatus = parseMaritalStatus(raw.maritalStatus());
            // L'état civil d'un producteur ne porte souvent que l'année.
            // Le modèle le prévoit avec birthYear ; l'import s'y range au
            // lieu de refuser la ligne ou d'inventer un 1er janvier, qui
            // finirait imprimé sur une carte producteur.
            Integer yearOnly = yearOnly(raw.birthDate());
            LocalDate birthDate = yearOnly != null
                    ? null
                    : parseDate(raw.birthDate(), "birthDate", issues);
            Integer birthYear = parseInt(raw.birthYear(), "birthYear", issues);
            if (birthYear == null) birthYear = yearOnly;
            LocalDate joinedAt = parseDate(raw.joinedAt(), "joinedAt", issues);
            LocalDate collectedAt = parseDate(raw.dataCollectedAt(), "dataCollectedAt", issues);
            BigDecimal parts = parseDecimal(raw.partsSocialesAmount(), "partsSocialesAmount", issues);

            // Le référentiel du tenant prime : un libellé proche d'un type
            // déjà connu s'y rattache, sinon on retient la forme canonique.
            String docType = trim(raw.idDocType());
            if (docType != null) {
                String matched = FuzzyLabels.bestMatch(docType, knownDocTypes);
                docType = matched != null ? matched : IdDocumentTypeCanonical.resolve(docType);
            }
            String sectionName = trim(raw.section());
            if (sectionName != null) {
                String matched = FuzzyLabels.bestMatch(sectionName, knownSections);
                if (matched != null) sectionName = matched;
            }

            Integer spouses = parseInt(raw.spousesCount(), "spousesCount", issues);
            Integer children = parseInt(raw.childrenCount(), "childrenCount", issues);
            Integer girls = parseInt(raw.girlsCount(), "girlsCount", issues);
            Integer boys = parseInt(raw.boysCount(), "boysCount", issues);
            Integer c0to4 = parseInt(raw.children0to4(), "children0to4", issues);
            Integer c5to17 = parseInt(raw.children5to17(), "children5to17", issues);
            Integer cOver17 = parseInt(raw.childrenOver17(), "childrenOver17", issues);
            Integer schooled = parseInt(raw.childrenSchooled(), "childrenSchooled", issues);
            Integer notSchooled = parseInt(raw.childrenNotSchooled(), "childrenNotSchooled", issues);

            List<FieldIssue> householdIssues = householdIssues(children, girls, boys,
                    c0to4, c5to17, cOver17, schooled, notSchooled);

            Normalized normalized = new Normalized(
                    trim(raw.code()),
                    recomposeName(lastName, firstName), firstName, lastName,
                    gender, personType, maritalStatus,
                    birthDate != null ? birthDate.format(ISO) : null, birthYear, trim(raw.birthPlace()),
                    docType, absent(raw.idDocNumber()), absent(raw.nationalIdNumber()),
                    trim(raw.externalCodeType()), absent(raw.externalCode()),
                    absent(raw.phone()), absent(raw.email()), trim(raw.village()), sectionName,
                    joinedAt != null ? joinedAt.format(ISO) : null, parts,
                    trim(raw.paymentMethod()), absent(raw.mobileMoneyNumber()),
                    spouses, children, girls, boys, c0to4, c5to17, cOver17,
                    schooled, notSchooled, trim(raw.childrenActivity()),
                    parseBoolean(raw.censusRegistered()), parseBoolean(raw.producerCardIssued()),
                    collectedAt != null ? collectedAt.format(ISO) : null,
                    trim(raw.notes()),
                    parseParcel(raw, knownParcels, nextParcelRank(raw, parcelRanks), issues),
                    trim(raw.delegateCode()));

            // Le village face au référentiel. Une ressemblance n'est jamais
            // appliquée d'office : une fusion ne se défait pas.
            LocalityMatch localityMatch = resolveLocality(
                    normalized.village(), trim(raw.localityId()), knownLocalities, sectionNameById);
            if (localityMatch != null
                    && localityMatch.status() == LocalityMatchStatus.SIMILAR) {
                issues.add(new FieldIssue("village", Messages.msg("m.mem-import-locality-ambiguous",
                        normalized.village(),
                        localityMatch.candidates().stream()
                                .map(LocalityMatch.Candidate::name)
                                .collect(java.util.stream.Collectors.joining(" » ou « ")))));
            }

            String key = dedupKey(normalized);
            MemberEntity match = findExisting(existing, normalized);

            Status status;
            UUID matchedId = match != null ? match.id : null;
            String matchedOn = match != null ? matchLabel(match, normalized) : null;

            if (!issues.isEmpty()) {
                status = Status.INVALID;
                invalid++;
            } else if (key != null && keysSeen.containsKey(raw.rowNumber()) ) {
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
            } else if (key != null && keysSeen.containsValue(key)) {
                // Le même producteur, une deuxième fois. Avec une parcelle,
                // c'est la façon dont il déclare ses plantations ; sans
                // parcelle, la ligne n'apporte rien et reste un doublon.
                if (normalized.parcel() != null) {
                    status = Status.ADDITIONAL_PARCEL;
                    additionalParcel++;
                } else {
                    issues.add(new FieldIssue("code", Messages.msg("m.imp-producer-duplicate-in-file")));
                    status = Status.DUPLICATE_IN_FILE;
                    duplicate++;
                }
            } else if (!householdIssues.isEmpty()) {
                issues.addAll(householdIssues);
                status = Status.WARNING;
                warning++;
            } else if (match != null) {
                status = Status.UPDATE;
                update++;
            } else {
                status = Status.READY;
                ready++;
            }
            if (key != null && status != Status.DUPLICATE_IN_FILE
                    && status != Status.ADDITIONAL_PARCEL) {
                keysSeen.put(raw.rowNumber(), key);
            }
            if (normalized.parcel() != null && status != Status.INVALID
                    && status != Status.DUPLICATE_IN_FILE) {
                if (normalized.parcel().matchedParcelId() != null) parcelsToUpdate++;
                else parcelsToCreate++;
                if (normalized.parcel().latitude() == null
                        || normalized.parcel().longitude() == null) parcelsWithoutPosition++;
            }

            rows.add(new Row(raw.rowNumber(), status, normalized, matchedId, matchedOn,
                    localityMatch, issues));
        }

        return new MemberImportPreviewDto(input.size(), ready, update, warning, invalid, duplicate,
                additionalParcel, parcelsToCreate, parcelsToUpdate,
                parcelsWithoutPosition, rows);
    }


    // ─── Parcelle portée par la ligne ───────────────────────────────

    /**
     * Lit la parcelle d'une ligne de producteur, ou rend null si la ligne
     * n'en porte pas.
     *
     * <p>Une parcelle demande au minimum un nom et un point GPS : sans
     * position, elle ne sert ni la traçabilité ni la conformité, et une
     * ligne à moitié remplie vaut mieux ignorée que créée creuse.</p>
     *
     * <p>Un code déjà connu rattache la ligne à la parcelle existante, qui
     * sera mise à jour. C'est ce qui empêche un réimport de doubler la
     * superficie de la coopérative.</p>
     */
    private MemberImportPreviewDto.Parcel parseParcel(MemberImportRowDto raw,
                                                      Map<String, UUID> knownParcels,
                                                      int parcelRank,
                                                      List<FieldIssue> issues) {
        String name = trim(raw.parcelName());
        String code = trim(raw.parcelCode());
        Double lat = parseCoordinate(raw.parcelLatitude(), "parcelLatitude", -90, 90, issues);
        Double lon = parseCoordinate(raw.parcelLongitude(), "parcelLongitude", -180, 180, issues);
        BigDecimal surface = parseDecimal(raw.parcelSurfaceHa(), "parcelSurfaceHa", issues);
        BigDecimal potential = parseDecimal(raw.parcelPotentialKg(), "parcelPotentialKg", issues);
        String crop = trim(raw.parcelCrop());
        String variety = trim(raw.parcelVariety());
        Integer plantingYear = parseInt(raw.parcelPlantingYear(), "parcelPlantingYear", issues);

        boolean anything = name != null || code != null || lat != null || lon != null
                || surface != null || potential != null || crop != null || variety != null
                || plantingYear != null || trim(raw.parcelRegion()) != null
                || trim(raw.parcelDepartment()) != null || trim(raw.parcelCertifications()) != null;
        if (!anything) return null;

        UUID matched = code != null ? knownParcels.get(code.toUpperCase(Locale.ROOT)) : null;

        // La position ne barre plus la route. Beaucoup de registres n'ont
        // pas encore été relevés au GPS, et refuser la ligne faisait perdre
        // le producteur avec sa parcelle. Le manque se compte dans le
        // récapitulatif : une parcelle sans position ne sert ni la
        // traçabilité ni le devoir de vigilance, et se situe plus tard.
        // Beaucoup de coopératives ne nomment pas leurs parcelles : elles
        // les repèrent par leur producteur et leur position. Exiger un nom
        // qu'aucun registre ne porte reviendrait à leur demander d'en
        // inventer un millier. Il se dérive du producteur et du rang de la
        // parcelle dans sa déclaration, ce qu'un opérateur écrirait de
        // toute façon, et reste modifiable ensuite.
        if (name == null && matched == null) {
            name = derivedParcelName(raw, parcelRank);
        }

        List<String> certifications = trim(raw.parcelCertifications()) == null
                ? List.of()
                : java.util.Arrays.stream(raw.parcelCertifications().split("[,;/]"))
                        .map(String::trim).filter(x -> !x.isEmpty()).toList();

        return new MemberImportPreviewDto.Parcel(
                code, name, surface, potential, crop, variety, plantingYear,
                lat, lon, trim(raw.parcelRegion()), trim(raw.parcelDepartment()),
                trim(raw.parcelStatus()), certifications, matched);
    }

    /** Coordonnée décimale, virgule ou point, bornée à son intervalle. */
    private Double parseCoordinate(String raw, String field, double min, double max,
                                   List<FieldIssue> issues) {
        BigDecimal value = parseDecimal(raw, field, issues);
        if (value == null) return null;
        double d = value.doubleValue();
        if (d < min || d > max) {
            issues.add(new FieldIssue(field, Messages.msg("m.imp-coordinate-out-of-range", field)));
            return null;
        }
        return d;
    }

    // ─── Application ────────────────────────────────────────────────

    /**
     * @param includeWarnings applique aussi les lignes au ménage incohérent.
     *                        L'utilisateur l'a alors décidé après les avoir
     *                        vues : on importe la donnée telle quelle plutôt
     *                        que de perdre le producteur.
     */
    public MemberImportCommitResponseDto commit(List<MemberImportRowDto> input, boolean includeWarnings) {
        MemberImportPreviewDto preview = preview(input);
        // Le plafond du plan se vérifie sur le lot entier, avant la première
        // écriture : un import qui s'arrêterait au producteur n° 412 sur 500
        // laisserait un état que personne ne saurait décrire. Seules les
        // créations comptent, une mise à jour ne consomme rien.
        int creations = (int) preview.rows().stream()
                .filter(r -> r.status() == Status.READY
                        || (r.status() == Status.WARNING && includeWarnings))
                .count();
        if (creations > 0) {
            planLimits.enforceMemberCapacity(creations);
        }
        List<UUID> created = new ArrayList<>();
        List<UUID> updated = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        LinkedHashSet<String> createdSections = new LinkedHashSet<>();
        LinkedHashSet<String> createdLocalities = new LinkedHashSet<>();
        LinkedHashSet<String> createdDocTypes = new LinkedHashSet<>();
        int householdsSkipped = 0;
        int parcelsCreated = 0;
        int parcelsUpdated = 0;

        // Le producteur d'une ligne supplémentaire est celui qu'une ligne
        // précédente a créé : sans cette mémoire, sa deuxième parcelle
        // n'aurait personne à qui appartenir.
        Map<String, UUID> memberByKey = new HashMap<>();

        for (Row row : preview.rows()) {
            boolean applicable = row.status() == Status.READY
                    || row.status() == Status.UPDATE
                    || row.status() == Status.ADDITIONAL_PARCEL
                    || (row.status() == Status.WARNING && includeWarnings);
            if (!applicable || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            try {
                // Ligne forcée : on importe le producteur, mais pas des
                // compteurs qui ne tombent pas juste. La règle métier reste
                // entière, et l'enquête sera refaite sur le terrain.
                Normalized n = row.normalized();
                if (row.status() == Status.WARNING) {
                    n = withoutHousehold(n);
                    householdsSkipped++;
                }
                UUID sectionId = resolveSection(n.sectionName(), createdSections);
                // Le village : rattaché s'il existe, créé s'il n'existe pas.
                // Les lignes ambiguës ne sont jamais arrivées jusqu'ici, la
                // ressemblance non tranchée les ayant écartées à l'aperçu.
                UUID localityId = resolveLocalityForCommit(row.localityMatch(), createdLocalities);
                ensureIdDocumentType(n.idDocType(), createdDocTypes);
                ensureIdDocumentType(externalCodeType(n), createdDocTypes, false, true);

                UUID memberId;
                if (row.status() == Status.ADDITIONAL_PARCEL) {
                    // La ligne n'apporte que sa parcelle : le producteur a
                    // été traité plus haut, on ne le réécrit pas.
                    memberId = memberByKey.get(dedupKey(n));
                    if (memberId == null) {
                        throw new IllegalStateException(Messages.msg("m.imp-parcel-owner-missing"));
                    }
                } else if (row.matchedMemberId() != null) {
                    MemberEntity cur = members.findById(row.matchedMemberId()).orElse(null);
                    MemberUpsertDto payload = cur != null
                            ? mergedUpsert(cur, n, sectionId, localityId)
                            : toUpsert(n, sectionId, localityId);
                    MemberResponseDto dto = memberService.update(row.matchedMemberId(), payload);
                    updated.add(dto.id());
                    memberId = dto.id();
                } else {
                    MemberResponseDto dto = memberService.create(toUpsert(n, sectionId, localityId));
                    created.add(dto.id());
                    memberId = dto.id();
                }
                String key = dedupKey(n);
                if (key != null) memberByKey.putIfAbsent(key, memberId);

                if (n.parcel() != null) {
                    if (applyParcel(n.parcel(), memberId)) parcelsCreated++;
                    else parcelsUpdated++;
                }
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(),
                        row.matchedMemberId(), row.matchedOn(), row.localityMatch(), issues));
            }
        }

        return new MemberImportCommitResponseDto(
                preview.totalRows(), created.size(), updated.size(), skipped.size(),
                created, updated,
                List.copyOf(createdSections), List.copyOf(createdDocTypes),
                householdsSkipped, parcelsCreated, parcelsUpdated,
                skipped);
    }


    /**
     * Crée ou met à jour la parcelle d'une ligne, et la rattache à son
     * producteur.
     *
     * @return {@code true} si la parcelle a été créée, {@code false} si
     *         elle existait et a été mise à jour
     */
    private boolean applyParcel(MemberImportPreviewDto.Parcel p, UUID memberId) {
        List<Double> center = p.longitude() != null && p.latitude() != null
                ? List.of(p.longitude(), p.latitude())
                : null;
        ParcelStatus status = parseParcelStatus(p.status());

        if (p.matchedParcelId() != null) {
            // La fiche existante est relue en DTO, comme le fait l'import
            // des parcelles : le contour GeoJSON n'a pas la même forme en
            // base et au contrat, et seul le DTO en donne la lecture.
            ParcelResponseDto current = parcelService.getById(p.matchedParcelId());
            // La ligne ne redit pas tout : ce qu'elle tait garde sa valeur.
            ParcelUpsertDto payload = new ParcelUpsertDto(
                    current.code(),
                    p.name() != null ? p.name() : current.name(),
                    p.surfaceHa() != null ? p.surfaceHa() : current.surfaceHa(),
                    current.gpsPolygonCoordinates(),
                    center != null ? center : current.gpsCenter(),
                    p.variety() != null ? p.variety() : current.variety(),
                    p.cropCode() != null ? p.cropCode() : current.cropCode(),
                    current.mainCrop(),
                    current.plantingDate(),
                    p.plantingYear() != null ? p.plantingYear() : current.plantingYear(),
                    p.regionCode() != null ? p.regionCode() : current.regionCode(),
                    p.departmentCode() != null ? p.departmentCode() : current.departmentCode(),
                    !p.certifications().isEmpty() ? p.certifications() : current.certifications(),
                    memberId,
                    null,
                    status != null ? status : current.status(),
                    current.notes());
            parcelService.update(p.matchedParcelId(), payload);
            return false;
        }

        parcelService.create(new ParcelUpsertDto(
                p.code(), p.name(), p.surfaceHa(), null, center,
                p.variety(), p.cropCode(), null, null, p.plantingYear(),
                p.regionCode(), p.departmentCode(), p.certifications(),
                memberId, List.of(),
                status != null ? status : ParcelStatus.ACTIVE, null));
        return true;
    }

    /** Statut de parcelle, tolérant : une valeur inconnue laisse le défaut. */
    private static ParcelStatus parseParcelStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (ParcelStatus s : ParcelStatus.values()) {
            if (s.name().equals(v)) return s;
        }
        return switch (v) {
            case "ACTIVE", "ACTIF", "ACTIVA" -> ParcelStatus.ACTIVE;
            default -> null;
        };
    }

    /** Reprend la ligne sans son ménage : le producteur entre, ses compteurs non. */
    private static Normalized withoutHousehold(Normalized n) {
        return new Normalized(
                n.code(), n.name(), n.firstName(), n.lastName(),
                n.gender(), n.personType(), n.maritalStatus(),
                n.birthDate(), n.birthYear(), n.birthPlace(),
                n.idDocType(), n.idDocNumber(), n.nationalIdNumber(),
                n.externalCodeType(), n.externalCode(),
                n.phone(), n.email(), n.village(), n.sectionName(),
                n.joinedAt(), n.partsSocialesAmount(),
                n.paymentMethod(), n.mobileMoneyNumber(),
                null, null, null, null, null, null, null, null, null, null,
                n.censusRegistered(), n.producerCardIssued(), n.dataCollectedAt(),
                n.notes(), n.parcel(), n.delegateCode());
    }

    // ─── Rapprochement ──────────────────────────────────────────────

    /**
     * Cherche le membre correspondant, du critère le plus sûr au plus
     * faible : code interne, code producteur externe, puis téléphone.
     */
    private static MemberEntity findExisting(List<MemberEntity> existing, Normalized n) {
        if (n.code() != null) {
            Optional<MemberEntity> byCode = existing.stream()
                    .filter(m -> n.code().equalsIgnoreCase(m.code))
                    .findFirst();
            if (byCode.isPresent()) return byCode.get();
        }
        String cardKey = com.ntech.cabosse.members.entity.MemberIdentityDocument
                .normalize(n.externalCode());
        if (cardKey != null) {
            Optional<MemberEntity> byCard = existing.stream()
                    .filter(m -> m.producerRefKeys != null && m.producerRefKeys.contains(cardKey))
                    .findFirst();
            if (byCard.isPresent()) return byCard.get();
        }
        String phone = digits(n.phone());
        if (phone != null && phone.length() >= 8) {
            Optional<MemberEntity> byPhone = existing.stream()
                    .filter(m -> phone.equals(digits(m.phone)))
                    .findFirst();
            if (byPhone.isPresent()) return byPhone.get();
        }
        return null;
    }

    private static String matchLabel(MemberEntity match, Normalized n) {
        if (n.code() != null && n.code().equalsIgnoreCase(match.code)) return "Code producteur";
        if (n.externalCode() != null) return "Code producteur externe";
        return "Téléphone";
    }

    private static String dedupKey(Normalized n) {
        if (n.code() != null) return "code:" + n.code().toLowerCase(Locale.ROOT);
        if (n.externalCode() != null) return "ext:" + n.externalCode().toLowerCase(Locale.ROOT);
        String phone = digits(n.phone());
        if (phone != null && phone.length() >= 8) return "tel:" + phone;
        return null;
    }

    // ─── Référentiels créés à la volée ──────────────────────────────

    private UUID resolveSection(String name, LinkedHashSet<String> createdSections) {
        if (name == null || name.isBlank()) return null;
        for (SectionEntity s : sections.listAll()) {
            if (FuzzyLabels.matches(s.name, name) || FuzzyLabels.matches(s.code, name)) return s.id;
        }
        SectionEntity created = new SectionEntity();
        created.id = idGenerator.newId();
        created.code = FuzzyLabels.canonical(name).replace(' ', '-').toUpperCase(Locale.ROOT);
        created.name = name.trim();
        created.active = true;
        created.createdAt = Instant.now();
        created.updatedAt = created.createdAt;
        sections.insert(created);
        createdSections.add(created.name);
        return created.id;
    }

    private void ensureIdDocumentType(String label, LinkedHashSet<String> createdDocTypes) {
        ensureIdDocumentType(label, createdDocTypes, true, false);
    }

    /**
     * Crée le type absent du référentiel avec l'usage que sa colonne
     * annonce : une pièce d'identité établit l'identité, un code producteur
     * externe sert à retrouver le producteur. Rien n'est deviné, chaque
     * colonne dit ce qu'elle contient.
     */
    private void ensureIdDocumentType(String label, LinkedHashSet<String> createdDocTypes,
                                      boolean identityProof, boolean usableAsProducerRef) {
        if (label == null || label.isBlank()) return;
        for (IdDocumentTypeEntity t : idDocumentTypes.listAll()) {
            if (FuzzyLabels.matches(t.name, label)) return;
        }
        IdDocumentTypeEntity created = new IdDocumentTypeEntity();
        created.id = idGenerator.newId();
        created.code = FuzzyLabels.canonical(label).replace(' ', '-');
        created.name = label.trim();
        created.identityProof = identityProof;
        created.usableAsProducerRef = usableAsProducerRef;
        created.active = true;
        created.createdAt = Instant.now();
        created.updatedAt = created.createdAt;
        idDocumentTypes.insert(created);
        createdDocTypes.add(created.name);
    }

    // ─── Conversion vers le payload métier ──────────────────────────

    /** Libellé du type de code externe porté par la ligne, à défaut générique. */
    private static String externalCodeType(Normalized n) {
        if (n.externalCode() == null) return null;
        return n.externalCodeType() != null ? n.externalCodeType() : "Carte producteur";
    }

    /**
     * Ligne UPDATE : fusion, pas remplacement (DEC-12, 26/08/2026). Le
     * fichier de recensement ne porte ni le mandat de délégué, ni le mobile
     * money, ni l'agent de suivi, ni les scans de pièces : tout ce qu'il ne
     * porte pas reste tel quel, et le statut ne se change jamais par import.
     * Corollaire : un import ne peut pas vider un champ, cela se fait à
     * l'écran.
     */
    private static MemberUpsertDto mergedUpsert(MemberEntity cur, Normalized n, UUID sectionId,
                                                UUID localityId) {
        // Pièces : celle du fichier remplace la pièce du même type quand le
        // numéro change ; sinon l'existante (scan, échéance) est conservée,
        // ainsi que toutes les pièces de types absents du fichier.
        List<MemberIdentityDocumentDto> docs = new ArrayList<>();
        if (cur.identityDocuments != null) {
            cur.identityDocuments.forEach(d -> docs.add(MemberIdentityDocumentDto.from(d)));
        }
        if (n.idDocNumber() != null) {
            upsertDoc(docs, new MemberIdentityDocumentDto(
                    n.idDocType() != null ? n.idDocType() : "Pièce d'identité",
                    n.idDocNumber(), null, null, null));
        }
        if (n.nationalIdNumber() != null) {
            upsertDoc(docs, new MemberIdentityDocumentDto(
                    "Identifiant national", n.nationalIdNumber(), null, null, null));
        }
        if (n.externalCode() != null) {
            upsertDoc(docs, new MemberIdentityDocumentDto(
                    externalCodeType(n), n.externalCode(), null, null, null));
        }

        // Le bloc ménage est une enquête : présent dans le fichier, il fait
        // foi en bloc ; absent, l'enquête existante reste.
        boolean householdProvided = n.spousesCount() != null || n.childrenCount() != null
                || n.girlsCount() != null || n.boysCount() != null
                || n.children0to4() != null || n.children5to17() != null
                || n.childrenOver17() != null || n.childrenSchooled() != null
                || n.childrenNotSchooled() != null || n.childrenActivity() != null;
        MemberHouseholdDto household = householdProvided
                ? new MemberHouseholdDto(
                        n.spousesCount(), n.childrenCount(), n.girlsCount(), n.boysCount(),
                        n.children0to4(), n.children5to17(), n.childrenOver17(),
                        n.childrenSchooled(), n.childrenNotSchooled(), n.childrenActivity())
                : MemberHouseholdDto.from(cur.household);

        MemberEnrolmentDto curEnrolment = MemberEnrolmentDto.from(cur.enrolment);
        MemberEnrolmentDto enrolment = new MemberEnrolmentDto(
                n.censusRegistered() != null ? n.censusRegistered() : curEnrolment.censusRegistered(),
                n.producerCardIssued() != null ? n.producerCardIssued() : curEnrolment.producerCardIssued(),
                n.dataCollectedAt() != null
                        ? LocalDate.parse(n.dataCollectedAt()) : curEnrolment.dataCollectedAt(),
                curEnrolment.dataCollectedByMemberId());

        return new MemberUpsertDto(
                cur.code, null, n.lastName(), n.firstName(),
                null,
                n.gender() != null
                        ? com.ntech.cabosse.members.entity.MemberGender.valueOf(n.gender())
                        : cur.gender,
                n.personType() != null
                        ? com.ntech.cabosse.members.entity.MemberPersonType.valueOf(n.personType())
                        : cur.personType,
                n.maritalStatus() != null
                        ? com.ntech.cabosse.members.entity.MemberMaritalStatus.valueOf(n.maritalStatus())
                        : cur.maritalStatus,
                n.birthPlace() != null ? n.birthPlace() : cur.birthPlace,
                MemberLegalIdentityDto.from(cur.legalIdentity),
                n.birthDate() != null ? LocalDate.parse(n.birthDate()) : cur.birthDate,
                n.birthYear() != null ? n.birthYear() : cur.birthYear,
                null, null, null, docs,
                household, enrolment,
                sectionId != null ? sectionId : cur.sectionId,
                cur.followUpAgentMemberId,
                cur.collector, cur.collectorMarginRate,
                cur.deliveredArticleIds != null ? cur.deliveredArticleIds : List.of(),
                List.of(),
                n.village() != null ? n.village() : cur.village,
                localityId != null ? localityId : cur.localityId,
                n.phone() != null ? n.phone() : cur.phone,
                n.email() != null ? n.email() : cur.email,
                n.joinedAt() != null ? LocalDate.parse(n.joinedAt()) : cur.joinedAt,
                n.partsSocialesAmount() != null ? n.partsSocialesAmount() : cur.partsSocialesAmount,
                cur.status,
                n.paymentMethod() != null ? n.paymentMethod() : cur.preferredPaymentMethod,
                n.mobileMoneyNumber() != null ? n.mobileMoneyNumber() : cur.mobileMoneyNumber,
                cur.mobileMoneyHolderName, cur.mobileMoneyMandateOnFile,
                n.notes() != null ? n.notes() : cur.notes);
    }

    /**
     * La localité d'une ligne au moment d'appliquer.
     *
     * <p>Rattachée si elle existe, créée si le village est nouveau. Le cas
     * ambigu n'arrive pas ici : la ressemblance non tranchée a écarté la
     * ligne à l'aperçu, où l'utilisateur a répondu.</p>
     */
    private UUID resolveLocalityForCommit(LocalityMatch match, java.util.Set<String> createdLocalities) {
        if (match == null) return null;
        if (match.localityId() != null) return match.localityId();
        if (match.status() != LocalityMatchStatus.NEW || match.localityName() == null) return null;

        // Une ligne plus haut a pu créer le même village : on le retrouve
        // plutôt que d'en créer un second.
        String existing = FuzzyLabels.exactMatch(match.localityName(),
                localities.listAll().stream().map(l -> l.name).toList());
        if (existing != null) {
            return localities.listAll().stream()
                    .filter(l -> existing.equals(l.name)).findFirst().map(l -> l.id).orElse(null);
        }
        var created = localityService.create(
                new com.ntech.cabosse.locality.dto.LocalityUpsertDto(null, match.localityName(), null));
        createdLocalities.add(created.name());
        return created.id();
    }

    /**
     * Ce que devient le village d'une ligne face au référentiel.
     *
     * <p>Trois cas, et surtout pas deux. Identique, on rattache. Aucun ne
     * ressemble, on créera. Un ou plusieurs ressemblent, <strong>on ne
     * tranche pas</strong> : rattacher au plus proche fusionnerait deux
     * villages voisins sans que personne ne l'ait voulu, et une fusion ne
     * se défait pas. La ligne porte alors ses candidats et attend un
     * choix.</p>
     *
     * @param chosenId localité déjà choisie pour cette ligne, le cas échéant
     */
    private LocalityMatch resolveLocality(
            String village, String chosenId,
            List<com.ntech.cabosse.locality.entity.LocalityEntity> known,
            Map<UUID, String> sectionNameById) {
        if (village == null || village.isBlank()) return null;

        // Un choix explicite clôt la question : c'est la réponse de
        // l'utilisateur à ce que le serveur ne pouvait pas trancher.
        if (chosenId != null && !chosenId.isBlank()) {
            UUID id = parseUuid(chosenId);
            var picked = id == null ? null
                    : known.stream().filter(l -> l.id.equals(id)).findFirst().orElse(null);
            if (picked != null) {
                return new LocalityMatch(LocalityMatchStatus.EXACT, picked.id, picked.name, List.of());
            }
        }

        List<String> names = known.stream().map(l -> l.name).toList();
        String exact = FuzzyLabels.exactMatch(village, names);
        if (exact != null) {
            var found = known.stream().filter(l -> exact.equals(l.name)).findFirst().orElse(null);
            return new LocalityMatch(LocalityMatchStatus.EXACT,
                    found != null ? found.id : null, exact, List.of());
        }

        List<String> near = FuzzyLabels.nearMatches(village, names, 3);
        if (!near.isEmpty()) {
            List<LocalityMatch.Candidate> candidates = new ArrayList<>();
            for (String name : near) {
                known.stream().filter(l -> name.equals(l.name)).findFirst().ifPresent(l ->
                        candidates.add(new LocalityMatch.Candidate(l.id, l.name,
                                l.sectionId != null ? sectionNameById.get(l.sectionId) : null)));
            }
            return new LocalityMatch(LocalityMatchStatus.SIMILAR, null, null, candidates);
        }

        return new LocalityMatch(LocalityMatchStatus.NEW, null, village.trim(), List.of());
    }

    private static UUID parseUuid(String raw) {
        try { return UUID.fromString(raw.trim()); } catch (RuntimeException e) { return null; }
    }

    /** Remplace la pièce du même type, sauf si le numéro est identique. */
    private static void upsertDoc(List<MemberIdentityDocumentDto> docs,
                                  MemberIdentityDocumentDto incoming) {
        String type = incoming.type().trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < docs.size(); i++) {
            MemberIdentityDocumentDto d = docs.get(i);
            if (d.type() != null && d.type().trim().toLowerCase(java.util.Locale.ROOT).equals(type)) {
                if (!incoming.number().equals(d.number())) docs.set(i, incoming);
                return;
            }
        }
        docs.add(incoming);
    }

    private static MemberUpsertDto toUpsert(Normalized n, UUID sectionId, UUID localityId) {
        List<MemberIdentityDocumentDto> docs = new ArrayList<>();
        if (n.idDocNumber() != null) {
            docs.add(new MemberIdentityDocumentDto(
                    n.idDocType() != null ? n.idDocType() : "Pièce d'identité",
                    n.idDocNumber(), null, null, null));
        }
        if (n.nationalIdNumber() != null) {
            docs.add(new MemberIdentityDocumentDto(
                    "Identifiant national", n.nationalIdNumber(), null, null, null));
        }
        // Le code externe est une pièce comme une autre : son type dit
        // simplement qu'il sert à retrouver le producteur.
        if (n.externalCode() != null) {
            docs.add(new MemberIdentityDocumentDto(
                    externalCodeType(n), n.externalCode(), null, null, null));
        }

        List<MemberExternalCodeDto> externals = List.of();

        MemberHouseholdDto household = new MemberHouseholdDto(
                n.spousesCount(), n.childrenCount(), n.girlsCount(), n.boysCount(),
                n.children0to4(), n.children5to17(), n.childrenOver17(),
                n.childrenSchooled(), n.childrenNotSchooled(), n.childrenActivity());

        MemberEnrolmentDto enrolment = new MemberEnrolmentDto(
                n.censusRegistered(), n.producerCardIssued(),
                n.dataCollectedAt() != null ? LocalDate.parse(n.dataCollectedAt()) : null,
                null);

        return new MemberUpsertDto(
                n.code(), null, n.lastName(), n.firstName(),
                null,
                n.gender() != null
                        ? com.ntech.cabosse.members.entity.MemberGender.valueOf(n.gender()) : null,
                n.personType() != null
                        ? com.ntech.cabosse.members.entity.MemberPersonType.valueOf(n.personType()) : null,
                n.maritalStatus() != null
                        ? com.ntech.cabosse.members.entity.MemberMaritalStatus.valueOf(n.maritalStatus())
                        : null,
                n.birthPlace(), null,
                n.birthDate() != null ? LocalDate.parse(n.birthDate()) : null,
                n.birthYear(),
                null, null, null, docs,
                household, enrolment,
                sectionId, null, /* collector */ null, /* collectorMarginRate */ null,
                List.of(), externals,
                n.village(), localityId, n.phone(), n.email(),
                n.joinedAt() != null ? LocalDate.parse(n.joinedAt()) : null,
                n.partsSocialesAmount(),
                MemberStatus.ACTIVE,
                n.paymentMethod(), n.mobileMoneyNumber(),
                // Titulaire du compte et mandat ne se saisissent pas en masse :
                // ils relèvent d'une vérification pièce en main.
                null, Boolean.FALSE,
                n.notes());
    }

    // ─── Contrôles et conversions ───────────────────────────────────

    private static List<FieldIssue> householdIssues(Integer children, Integer girls, Integer boys,
                                                    Integer c0to4, Integer c5to17, Integer cOver17,
                                                    Integer schooled, Integer notSchooled) {
        List<FieldIssue> issues = new ArrayList<>();
        if (children != null && girls != null && boys != null && girls + boys != children) {
            issues.add(new FieldIssue("girlsCount", Messages.msg("m.imp-girls-boys-mismatch",
                    String.valueOf(girls + boys), String.valueOf(children))));
        }
        if (children != null && c0to4 != null && c5to17 != null && cOver17 != null
                && c0to4 + c5to17 + cOver17 != children) {
            issues.add(new FieldIssue("children0to4", Messages.msg("m.imp-age-brackets-mismatch",
                    String.valueOf(c0to4 + c5to17 + cOver17), String.valueOf(children))));
        }
        if (children != null && schooled != null && notSchooled != null
                && schooled + notSchooled > children) {
            issues.add(new FieldIssue("childrenSchooled", Messages.msg("m.imp-schooled-exceeds-children")));
        }
        return issues;
    }

    private static String parseGender(String raw, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        String c = FuzzyLabels.canonical(raw);
        if (c.startsWith("h") || c.startsWith("m")) return "MALE";
        if (c.startsWith("f")) return "FEMALE";
        issues.add(new FieldIssue("gender", Messages.msg("m.imp-gender-unknown", raw)));
        return null;
    }

    /** Personne physique ou morale, dans les deux langues servies. */
    private static String parsePersonType(String raw) {
        if (raw == null || raw.isBlank()) return "NATURAL_PERSON";
        String c = FuzzyLabels.canonical(raw);
        return c.contains("moral") || c.contains("societe") || c.contains("groupement")
                || c.contains("legal") || c.contains("compan") || c.contains("entity")
                ? "LEGAL_ENTITY" : "NATURAL_PERSON";
    }

    /**
     * Situation matrimoniale, lue dans les deux langues servies.
     *
     * <p>Le modèle d'import s'affiche dans la langue de l'utilisateur : un
     * anglophone y recopie « Married », et sans les formes anglaises ici la
     * colonne revenait vide, sans erreur. Traduire ce qui s'affiche oblige à
     * élargir ce qui se relit, sous peine de casser le circuit en silence.</p>
     */
    private static String parseMaritalStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String c = FuzzyLabels.canonical(raw);
        if (c.startsWith("marie") || c.startsWith("married")) return "MARRIED";
        if (c.startsWith("celibataire") || c.startsWith("single")) return "SINGLE";
        if (c.startsWith("veu") || c.startsWith("widow")) return "WIDOWED";
        if (c.startsWith("divorce")) return "DIVORCED";
        if (c.contains("libre") || c.contains("concubin")
                || c.contains("cohabit") || c.contains("partner")) return "COHABITING";
        return null;
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String c = FuzzyLabels.canonical(raw);
        if (c.startsWith("oui") || c.equals("o") || c.startsWith("yes") || c.equals("x")
                || c.equals("1") || c.startsWith("vrai")) return Boolean.TRUE;
        if (c.startsWith("non") || c.equals("n") || c.equals("no") || c.equals("0")
                || c.startsWith("faux") || c.startsWith("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Rang de la parcelle suivante chez ce producteur, à partir de 1. */
    private static int nextParcelRank(MemberImportRowDto raw, Map<String, Integer> ranks) {
        String who = trim(raw.code());
        if (who == null) who = recomposeName(trim(raw.lastName()), trim(raw.firstName()));
        if (who == null) who = "?" + raw.rowNumber();
        return ranks.merge(who.toUpperCase(Locale.ROOT), 1, Integer::sum);
    }

    /**
     * Année seule, telle que « 1979 ».
     *
     * <p>Bornée à une plage plausible pour ne pas prendre un code à quatre
     * chiffres pour une année de naissance.</p>
     */
    private static Integer yearOnly(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.matches("\\d{4}")) return null;
        int year = Integer.parseInt(value);
        return (year >= 1900 && year <= LocalDate.now().getYear()) ? year : null;
    }

    /**
     * Nom de repli d'une parcelle que le registre ne nomme pas.
     *
     * <p>Le producteur d'abord, parce que c'est ainsi qu'on la désigne sur
     * le terrain ; son rang ensuite, parce qu'un producteur en déclare
     * plusieurs.</p>
     */
    private static String derivedParcelName(MemberImportRowDto raw, int rank) {
        String who = trim(raw.code());
        if (who == null) who = recomposeName(trim(raw.lastName()), trim(raw.firstName()));
        String label = Messages.msg("m.imp-parcel-name-derived", String.valueOf(rank));
        return who == null ? label : who + " " + label;
    }

    private static LocalDate parseDate(String raw, String field, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (RuntimeException ignored) {
                // format suivant
            }
        }
        issues.add(new FieldIssue(field, Messages.msg("m.imp-date-unreadable", raw)));
        return null;
    }

    private static Integer parseInt(String raw, String field, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.valueOf(raw.trim().replaceAll(BLANKS, ""));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, Messages.msg("m.imp-number-unreadable", raw)));
            return null;
        }
    }

    private static BigDecimal parseDecimal(String raw, String field, List<FieldIssue> issues) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim().replaceAll(BLANKS, "").replace(',', '.'));
        } catch (NumberFormatException e) {
            issues.add(new FieldIssue(field, Messages.msg("m.imp-amount-unreadable", raw)));
            return null;
        }
    }

    private static String recomposeName(String lastName, String firstName) {
        String composed = (lastName == null ? "" : lastName) + " " + (firstName == null ? "" : firstName);
        composed = composed.trim().replaceAll("\\s+", " ");
        return composed.isEmpty() ? null : composed;
    }

    private static String digits(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Ce que le registre écrit à la place d'une valeur qu'il n'a pas.
     *
     * <p>Sur le fichier de septembre 2026, 691 téléphones et 172 numéros de
     * pièce valent « non disponible ». Recopiés tels quels, ils remplissent
     * l'annuaire et les exports d'une phrase à la place d'un numéro, et un
     * filtre « producteurs sans téléphone » ne trouve plus personne alors
     * que le quart de la coopérative est injoignable.</p>
     */
    private static final java.util.Set<String> ABSENT = java.util.Set.of(
            "non disponible", "non-disponible", "nondisponible", "indisponible",
            "non renseigne", "non-renseigne", "non communique", "non-communique",
            "neant", "aucun", "aucune", "inconnu", "inconnue", "rien",
            "na", "n a", "n/a", "nd", "n d", "-", "--", "...",
            "not available", "unavailable", "none", "unknown", "no phone");

    /**
     * Vide un champ facultatif que le fichier remplit d'un « non disponible ».
     *
     * <p>Réservé aux champs dont l'absence n'empêche rien : téléphone,
     * courriel, numéros de pièce, mobile money. Appliqué au nom, la même
     * tolérance transformerait une ligne qui passe en ligne refusée, et
     * c'est exactement ce qu'on cherche à éviter.</p>
     */
    private static String absent(String s) {
        String v = trim(s);
        if (v == null) return null;
        return ABSENT.contains(FuzzyLabels.canonical(v)) ? null : v;
    }
}
