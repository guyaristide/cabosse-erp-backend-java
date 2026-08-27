package com.ntech.cabosse.members.service;

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
    @Inject IdDocumentTypeRepository idDocumentTypes;
    @Inject IdGenerator idGenerator;

    // ─── Aperçu ─────────────────────────────────────────────────────

    public MemberImportPreviewDto preview(List<MemberImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new MemberImportPreviewDto(0, 0, 0, 0, 0, 0, List.of());
        }

        List<MemberEntity> existing = members.listAll();
        Map<Integer, String> keysSeen = new HashMap<>();
        List<String> knownSections = sections.listAll().stream().map(s -> s.name).toList();
        List<String> knownDocTypes = idDocumentTypes.listAll().stream().map(t -> t.name).toList();

        List<Row> rows = new ArrayList<>(input.size());
        int ready = 0, update = 0, warning = 0, invalid = 0, duplicate = 0;

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
            LocalDate birthDate = parseDate(raw.birthDate(), "birthDate", issues);
            Integer birthYear = parseInt(raw.birthYear(), "birthYear", issues);
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
                    docType, trim(raw.idDocNumber()), trim(raw.nationalIdNumber()),
                    trim(raw.externalCodeType()), trim(raw.externalCode()),
                    trim(raw.phone()), trim(raw.email()), trim(raw.village()), sectionName,
                    joinedAt != null ? joinedAt.format(ISO) : null, parts,
                    trim(raw.paymentMethod()), trim(raw.mobileMoneyNumber()),
                    spouses, children, girls, boys, c0to4, c5to17, cOver17,
                    schooled, notSchooled, trim(raw.childrenActivity()),
                    parseBoolean(raw.censusRegistered()), parseBoolean(raw.producerCardIssued()),
                    collectedAt != null ? collectedAt.format(ISO) : null,
                    trim(raw.notes()));

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
                issues.add(new FieldIssue("code", Messages.msg("m.imp-producer-duplicate-in-file")));
                status = Status.DUPLICATE_IN_FILE;
                duplicate++;
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
            if (key != null && status != Status.DUPLICATE_IN_FILE) keysSeen.put(raw.rowNumber(), key);

            rows.add(new Row(raw.rowNumber(), status, normalized, matchedId, matchedOn, issues));
        }

        return new MemberImportPreviewDto(input.size(), ready, update, warning, invalid, duplicate, rows);
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
        LinkedHashSet<String> createdDocTypes = new LinkedHashSet<>();
        int householdsSkipped = 0;

        for (Row row : preview.rows()) {
            boolean applicable = row.status() == Status.READY
                    || row.status() == Status.UPDATE
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
                ensureIdDocumentType(n.idDocType(), createdDocTypes);
                ensureIdDocumentType(externalCodeType(n), createdDocTypes, false, true);

                if (row.matchedMemberId() != null) {
                    MemberEntity cur = members.findById(row.matchedMemberId()).orElse(null);
                    MemberUpsertDto payload = cur != null
                            ? mergedUpsert(cur, n, sectionId)
                            : toUpsert(n, sectionId);
                    MemberResponseDto dto = memberService.update(row.matchedMemberId(), payload);
                    updated.add(dto.id());
                } else {
                    MemberResponseDto dto = memberService.create(toUpsert(n, sectionId));
                    created.add(dto.id());
                }
            } catch (RuntimeException e) {
                List<FieldIssue> issues = new ArrayList<>(row.issues());
                issues.add(new FieldIssue("server", e.getMessage()));
                skipped.add(new Row(row.rowNumber(), Status.INVALID, row.normalized(),
                        row.matchedMemberId(), row.matchedOn(), issues));
            }
        }

        return new MemberImportCommitResponseDto(
                preview.totalRows(), created.size(), updated.size(), skipped.size(),
                created, updated,
                List.copyOf(createdSections), List.copyOf(createdDocTypes),
                householdsSkipped,
                skipped);
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
                n.notes());
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
    private static MemberUpsertDto mergedUpsert(MemberEntity cur, Normalized n, UUID sectionId) {
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

    private static MemberUpsertDto toUpsert(Normalized n, UUID sectionId) {
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
                n.village(), n.phone(), n.email(),
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

    private static String parsePersonType(String raw) {
        if (raw == null || raw.isBlank()) return "NATURAL_PERSON";
        String c = FuzzyLabels.canonical(raw);
        return c.contains("moral") || c.contains("societe") || c.contains("groupement")
                ? "LEGAL_ENTITY" : "NATURAL_PERSON";
    }

    private static String parseMaritalStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String c = FuzzyLabels.canonical(raw);
        if (c.startsWith("marie")) return "MARRIED";
        if (c.startsWith("celibataire")) return "SINGLE";
        if (c.startsWith("veu")) return "WIDOWED";
        if (c.startsWith("divorce")) return "DIVORCED";
        if (c.contains("libre") || c.contains("concubin")) return "COHABITING";
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
}
