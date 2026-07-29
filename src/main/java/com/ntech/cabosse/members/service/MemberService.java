package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.dto.MemberExternalCodeDto;
import com.ntech.cabosse.members.dto.MemberIdentityDocumentDto;
import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberCivilStatus;
import com.ntech.cabosse.members.entity.MemberEnrolment;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberGender;
import com.ntech.cabosse.members.entity.MemberHousehold;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.members.entity.MemberMaritalStatus;
import com.ntech.cabosse.members.entity.MemberPersonType;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD des membres-producteurs. Auto-crée un {@link SupplierEntity}
 * miroir à la création pour que les flux d'achats (BC, RD) puissent
 * référencer un fournisseur sans modification — cf. NEIBA-TECH-2026-003 §2.
 *
 * <p>Toute mutation de membre est garde-fou : le service présuppose que
 * la capacité {@code HAS_MEMBERS} est active pour le tenant courant.
 * La vérification est faite au niveau du {@code MemberResource}
 * (RolesAllowed + filtre capability).</p>
 */
@ApplicationScoped
public class MemberService {

    @Inject MemberRepository members;
    @Inject MemberRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject IdGenerator idGenerator;
    @Inject ProducerRefKeyService producerRefKeys;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<MemberResponseDto> page(String q,
                                              com.ntech.cabosse.members.entity.MemberStatus statusFilter,
                                              PageRequest pr) {
        long total = members.countSearch(q, statusFilter);
        int validityMonths = fileValidityMonths();
        java.util.Set<String> proofTypes = producerRefKeys.identityProofTypeNames();
        List<MemberResponseDto> items = members.search(q, statusFilter, pr.skip(), pr.perPage())
                .stream()
                .map(m -> MemberResponseDto.from(m, validityMonths, proofTypes))
                .toList();
        Map<String, String> filters = new HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (statusFilter != null) filters.put("status", statusFilter.name());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public MemberResponseDto getById(UUID id) {
        return MemberResponseDto.from(loadOrFail(id), fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    // ─── Création ───────────────────────────────────────────────────

    public MemberResponseDto create(MemberUpsertDto payload) {
        MemberEntity e = new MemberEntity();
        e.id = idGenerator.newId();
        e.code = resolveCode(payload.code());
        applyPayload(e, payload);
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // Auto-création du SupplierEntity miroir — différée tant que le
        // dossier d'adhésion n'est pas validé : un membre en attente ne
        // doit pas apparaître dans les flux d'achats.
        if (e.status != MemberStatus.PENDING) {
            SupplierEntity supplier = createMirrorSupplier(e);
            e.supplierId = supplier.id;
        }

        members.insert(e);
        if (e.status != MemberStatus.PENDING) {
            postCapitalIfEnabled(e);
        }
        return MemberResponseDto.from(e, fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    // ─── Workflow d'adhésion (backlog MEM-01) ───────────────────────

    /** Valide le dossier : membre actif, fournisseur miroir créé si absent. */
    public MemberResponseDto approve(UUID id) {
        MemberEntity e = loadOrFail(id);
        if (e.status != MemberStatus.PENDING) {
            throw new BusinessException(
                    "Seul un dossier en attente peut être validé (statut actuel : " + e.status + ").");
        }
        e.status = MemberStatus.ACTIVE;
        e.statusReason = null;
        e.approvedAt = Instant.now();
        e.approvedBy = safeUserId();
        if (e.joinedAt == null) e.joinedAt = LocalDate.now();
        if (e.supplierId == null) {
            SupplierEntity supplier = createMirrorSupplier(e);
            e.supplierId = supplier.id;
        }
        e.updatedAt = Instant.now();
        members.replace(e);
        postCapitalIfEnabled(e);

        audit.event(AuditEventType.MEMBER_APPLICATION_APPROVED)
                .actorEmail(actor())
                .target("member", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Adhésion validée : " + e.name + " (" + e.code + ")")
                .record();
        return MemberResponseDto.from(e, fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    /** Rejette le dossier avec motif : le membre passe inactif, motif tracé. */
    public MemberResponseDto reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif de rejet requis.");
        }
        MemberEntity e = loadOrFail(id);
        if (e.status != MemberStatus.PENDING) {
            throw new BusinessException(
                    "Seul un dossier en attente peut être rejeté (statut actuel : " + e.status + ").");
        }
        e.status = MemberStatus.INACTIVE;
        e.statusReason = reason.trim();
        e.updatedAt = Instant.now();
        members.replace(e);

        audit.event(AuditEventType.MEMBER_APPLICATION_REJECTED)
                .actorEmail(actor())
                .target("member", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Adhésion rejetée : " + e.name + " (" + e.code + ") : " + e.statusReason)
                .record();
        return MemberResponseDto.from(e, fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    // ─── Update ─────────────────────────────────────────────────────

    public MemberResponseDto update(UUID id, MemberUpsertDto payload) {
        MemberEntity e = loadOrFail(id);
        if (payload.status() == MemberStatus.RETIRED && e.status != MemberStatus.RETIRED) {
            throw new BusinessException(
                    "La radiation passe par l'action dédiée (remboursement des parts et clôture).");
        }
        String previousPaymentDetails = paymentDetails(e);
        applyPayload(e, payload);
        auditPaymentDetailsChange(e, previousPaymentDetails);
        e.updatedAt = Instant.now();
        members.replace(e);

        syncMirrorSupplier(e);
        return MemberResponseDto.from(e, fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Radiation (backlog MEM-05) : le membre sort de la structure, ses
     * parts sociales sont remboursées par contre-passation de la pièce
     * d'adhésion (si elle existe), son fournisseur miroir est désactivé
     * et la fiche est close. Autorisée depuis ACTIVE ou SUSPENDED.
     */
    public MemberResponseDto retire(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Motif de radiation requis.");
        }
        MemberEntity e = loadOrFail(id);
        if (e.status != MemberStatus.ACTIVE && e.status != MemberStatus.SUSPENDED) {
            throw new BusinessException(
                    "Seul un membre actif ou suspendu peut être radié (statut actuel : "
                            + e.status + ").");
        }
        // Solde des parts sociales : miroir exact de la pièce d'adhésion,
        // no-op si aucune pièce capital n'existait (montant nul ou
        // écriture désactivée par le tenant à l'époque). Idempotent.
        accounting.reverseFrom(
                com.ntech.cabosse.accounting.entity.PostingSourceType.MEMBER_CAPITAL_LIBERATION,
                e.id,
                "Radiation " + e.name);
        accounting.reverseFrom(
                com.ntech.cabosse.accounting.entity.PostingSourceType.MEMBER_CAPITAL,
                e.id,
                "Radiation " + e.name);

        if (e.supplierId != null) {
            suppliers.findById(e.supplierId).ifPresent(s -> {
                s.active = false;
                s.updatedAt = Instant.now();
                suppliers.replace(s);
            });
        }

        e.status = MemberStatus.RETIRED;
        e.statusReason = reason.trim();
        e.updatedAt = Instant.now();
        members.replace(e);

        audit.event(AuditEventType.MEMBER_RETIRED)
                .actorEmail(actor())
                .target("member", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Radiation : " + e.name + " (" + e.code + ") : " + e.statusReason)
                .record();
        return MemberResponseDto.from(e, fileValidityMonths(), producerRefKeys.identityProofTypeNames());
    }

    /**
     * Pièce « part sociale » à l'adhésion (backlog MEM-02), pilotée par
     * les préférences tenant : interrupteur {@code postMemberCapitalEntries}
     * et compte {@code memberCapitalAccount} (défaut 101). Trésorerie :
     * caisse (571) sauf mode de paiement préféré évoquant un virement ou
     * du mobile money (521). Idempotent par membre : un second appel
     * (re-validation) ne double pas la pièce.
     */
    private void postCapitalIfEnabled(MemberEntity e) {
        if (e.partsSocialesAmount == null || e.partsSocialesAmount.signum() <= 0) return;
        TenantPreferences prefs = preferences.current();
        if (!prefs.postMemberCapitalEntries()) return;
        if (TenantPreferences.CAPITAL_FLOW_SUBSCRIPTION.equals(prefs.memberCapitalFlow())) {
            accounting.postFromMemberCapitalSubscription(
                    e.id,
                    e.name + " (" + e.code + ")",
                    e.partsSocialesAmount,
                    e.joinedAt,
                    prefs.memberCapitalAccount(),
                    capitalTreasuryAccountFor(e.preferredPaymentMethod)
            );
        } else {
            accounting.postFromMemberCapital(
                    e.id,
                    e.name + " (" + e.code + ")",
                    e.partsSocialesAmount,
                    e.joinedAt,
                    prefs.memberCapitalAccount(),
                    capitalTreasuryAccountFor(e.preferredPaymentMethod)
            );
        }
    }

    /** Heuristique sur le texte libre du mode de paiement : espèces par défaut. */
    private static String capitalTreasuryAccountFor(String preferredPaymentMethod) {
        if (preferredPaymentMethod == null) return SyscohadaAccounts.CAISSE_DEFAULT;
        String normalized = preferredPaymentMethod.toLowerCase(java.util.Locale.ROOT);
        boolean bankLike = normalized.contains("vir") || normalized.contains("banque")
                || normalized.contains("mobile") || normalized.contains("money")
                || normalized.contains("wave") || normalized.contains("orange")
                || normalized.contains("mtn") || normalized.contains("moov");
        return bankLike ? SyscohadaAccounts.BANQUE_DEFAULT : SyscohadaAccounts.CAISSE_DEFAULT;
    }

    private MemberEntity loadOrFail(UUID id) {
        return members.findById(id)
                .orElseThrow(() -> new NotFoundException("Membre " + id + " introuvable."));
    }

    /**
     * Trace tout changement de coordonnées de paiement (backlog MEM-12).
     * Inconditionnel : c'est le point d'entrée d'un détournement, et le
     * journal ne coûte rien. La vigilance bloquante, elle, reste sous
     * préférence tenant.
     */
    private void auditPaymentDetailsChange(MemberEntity e, String previous) {
        String current = paymentDetails(e);
        if (current.equals(previous)) return;
        audit.event(AuditEventType.MEMBER_PAYMENT_DETAILS_CHANGED)
                .actorEmail(actor())
                .target("member", e.id.toString(), e.name)
                .tenant(tenantContext.tenantId(), null)
                .description("Coordonnées de paiement modifiées pour " + e.name
                        + " (" + e.code + ") : " + previous + " → " + current)
                .record();
    }

    /** Empreinte lisible des coordonnées de paiement, pour le journal d'audit. */
    private static String paymentDetails(MemberEntity e) {
        return "mode=" + nullToDash(e.preferredPaymentMethod)
                + ", numéro=" + nullToDash(e.mobileMoneyNumber)
                + ", titulaire=" + nullToDash(e.mobileMoneyHolderName)
                + ", mandat=" + (e.mobileMoneyMandateOnFile ? "oui" : "non");
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /**
     * Durée de validité d'une enquête producteur, en mois (backlog MEM-09).
     * Best-effort : hors contexte tenant (tests unitaires, tâches internes),
     * on retombe sur le défaut plutôt que d'échouer une lecture.
     */
    private int fileValidityMonths() {
        try {
            return preferences.current().producerFileValidityMonths();
        } catch (RuntimeException e) {
            return TenantPreferences.DEFAULT_PRODUCER_FILE_VALIDITY_MONTHS;
        }
    }

    /**
     * Résout le code d'un membre à la création : code saisi (unique) sinon
     * séquence {@code MB-YYYY-NNNN} (backlog MEM-06).
     */
    private String resolveCode(String provided) {
        if (provided == null || provided.isBlank()) {
            return refService.next();
        }
        String code = provided.trim();
        if (members.codeExists(code)) {
            throw new BusinessException("Un membre avec le code « " + code + " » existe déjà.");
        }
        return code;
    }

    /** Recompose le nom affiché : {@code Nom Prénoms}, espaces normalisés. */
    private static String recomposeName(String lastName, String firstName) {
        String composed = firstName == null ? lastName : lastName + " " + firstName;
        return composed.trim().replaceAll("\\s+", " ");
    }

    private void applyPayload(MemberEntity e, MemberUpsertDto p) {
        String lastName = blankToNull(p.lastName());
        String firstName = blankToNull(p.firstName());
        if (lastName == null) {
            // Rétrocompat : ancien client qui n'envoie que `name`.
            lastName = blankToNull(p.name());
            if (lastName == null) {
                throw new BusinessException("Le nom du membre est requis.");
            }
        }
        e.lastName = lastName;
        e.firstName = firstName;
        e.name = recomposeName(lastName, firstName);
        applyIdentity(e, p);
        e.birthDate = p.birthDate();
        e.birthYear = p.birthYear();
        applyIdentityDocuments(e, p);
        e.household = p.household() == null ? new MemberHousehold() : p.household().toEntity();
        MemberHouseholdRules.validate(e.household);
        e.enrolment = p.enrolment() == null ? new MemberEnrolment() : p.enrolment().toEntity();
        e.sectionId = p.sectionId();
        e.collector = p.collector() != null && p.collector();
        e.collectorMarginRate = e.collector ? p.collectorMarginRate() : null;
        e.followUpAgentMemberId = p.followUpAgentMemberId();
        e.deliveredArticleIds = p.deliveredArticleIds() == null ? new ArrayList<>()
                : p.deliveredArticleIds().stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct().collect(Collectors.toList());
        e.village = blankToNull(p.village());
        e.phone = blankToNull(p.phone());
        e.email = blankToNull(p.email());
        e.joinedAt = p.joinedAt();
        e.partsSocialesAmount = p.partsSocialesAmount();
        e.status = p.status();
        e.preferredPaymentMethod = blankToNull(p.preferredPaymentMethod());
        e.mobileMoneyNumber = blankToNull(p.mobileMoneyNumber());
        e.mobileMoneyHolderName = blankToNull(p.mobileMoneyHolderName());
        e.mobileMoneyMandateOnFile = Boolean.TRUE.equals(p.mobileMoneyMandateOnFile());
        e.notes = blankToNull(p.notes());
    }

    /**
     * Genre, nature juridique, situation matrimoniale (backlog MEM-07).
     *
     * <p>Le champ legacy {@code civilStatus} mélangeait genre et nature
     * juridique. On accepte les deux sens de lecture : un client récent
     * envoie {@code gender} / {@code personType} et le legacy est recalculé
     * pour les lecteurs existants (registre, exports) ; un client ancien
     * n'envoie que {@code civilStatus} et les champs dédiés en sont
     * dérivés.</p>
     */
    private static void applyIdentity(MemberEntity e, MemberUpsertDto p) {
        MemberGender gender = p.gender();
        MemberPersonType personType = p.personType();

        if (gender == null && personType == null && p.civilStatus() != null) {
            gender = switch (p.civilStatus()) {
                case MALE -> MemberGender.MALE;
                case FEMALE -> MemberGender.FEMALE;
                default -> MemberGender.UNKNOWN;
            };
            personType = p.civilStatus() == MemberCivilStatus.LEGAL_ENTITY
                    ? MemberPersonType.LEGAL_ENTITY
                    : MemberPersonType.NATURAL_PERSON;
        }

        e.gender = gender != null ? gender : MemberGender.UNKNOWN;
        e.personType = personType != null ? personType : MemberPersonType.NATURAL_PERSON;
        e.maritalStatus = p.maritalStatus() != null ? p.maritalStatus() : MemberMaritalStatus.UNKNOWN;
        e.birthPlace = blankToNull(p.birthPlace());
        e.civilStatus = deriveCivilStatus(e.gender, e.personType);

        e.legalIdentity = e.personType == MemberPersonType.LEGAL_ENTITY && p.legalIdentity() != null
                ? p.legalIdentity().toEntity()
                : null;
    }

    /** Valeur legacy équivalente, pour les lecteurs qui n'ont pas migré. */
    private static MemberCivilStatus deriveCivilStatus(MemberGender gender, MemberPersonType type) {
        if (type == MemberPersonType.LEGAL_ENTITY) return MemberCivilStatus.LEGAL_ENTITY;
        return switch (gender) {
            case MALE -> MemberCivilStatus.MALE;
            case FEMALE -> MemberCivilStatus.FEMALE;
            case UNKNOWN -> MemberCivilStatus.UNKNOWN;
        };
    }

    /**
     * Pièces d'identité (backlog MEM-07). La liste fait foi ; le triplet
     * legacy {@code idDocType / idDocNumber / idCardFileId} reflète sa
     * première entrée pour que le registre producteurs et les exports
     * continuent de fonctionner sans modification. Un client ancien qui
     * n'envoie que le triplet alimente la liste.
     */
    private void applyIdentityDocuments(MemberEntity e, MemberUpsertDto p) {
        List<MemberIdentityDocument> docs = p.identityDocuments() == null ? new ArrayList<>()
                : p.identityDocuments().stream()
                        .filter(d -> d != null && d.number() != null && !d.number().isBlank())
                        .map(MemberIdentityDocumentDto::toEntity)
                        .collect(Collectors.toCollection(ArrayList::new));

        if (docs.isEmpty() && blankToNull(p.idDocNumber()) != null) {
            docs.add(new MemberIdentityDocument(
                    blankToNull(p.idDocType()), blankToNull(p.idDocNumber()), p.idCardFileId()));
        }

        e.identityDocuments = docs;

        // Le triplet legacy représente la pièce qui établit l'identité, pas
        // la première de la liste : une carte de filière n'a rien à faire
        // dans la colonne « pièce d'identité » du registre.
        Set<String> proofs = producerRefKeys.identityProofTypeNames();
        MemberIdentityDocument first = docs.stream()
                .filter(d -> proofs == null || (d.type != null
                        && proofs.contains(d.type.trim().toLowerCase(java.util.Locale.ROOT))))
                .findFirst()
                .orElse(null);
        e.idDocType = first != null ? first.type : null;
        e.idDocNumber = first != null ? first.number : null;
        e.idCardFileId = first != null ? first.fileId : null;

        // Clés de rapprochement : dérivées des pièces dont le type sert
        // d'identifiant. Le contrôle d'unicité nomme le porteur en cas de
        // collision, plutôt que de laisser l'index refuser sans explication.
        e.producerRefKeys = producerRefKeys.keysOf(docs);
        producerRefKeys.ensureAvailable(e.producerRefKeys, e.id);

        // Miroir de l'ancienne liste, pour les lecteurs non encore repris.
        Set<String> identifiers = producerRefKeys.identifierTypeNames();
        e.externalProducerCodes = docs.stream()
                .filter(d -> d.type != null
                        && identifiers.contains(d.type.trim().toLowerCase(java.util.Locale.ROOT)))
                .map(d -> new com.ntech.cabosse.members.entity.MemberExternalCode(d.type, d.number))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Reporte sur le fournisseur miroir ce que la fiche du producteur porte :
     * son identité de contact, et sa qualité de délégué. Les avances ne
     * connaissent que le fournisseur ; sans ce report, cocher la case sur le
     * producteur n'aurait aucun effet.
     *
     * <p>La section du délégué est celle du producteur : c'est la même zone
     * de collecte, et la redemander ouvrirait la porte à deux réponses
     * différentes pour une seule réalité.</p>
     */
    private void syncMirrorSupplier(MemberEntity e) {
        if (e.supplierId == null) return;
        suppliers.findById(e.supplierId).ifPresent(s -> {
            s.name = e.name;
            s.phone = e.phone;
            s.email = e.email;
            s.cityName = e.village;
            s.collector = e.collector;
            s.sectionId = e.collector ? e.sectionId : null;
            s.collectorMarginRate = e.collector ? e.collectorMarginRate : null;
            s.updatedAt = Instant.now();
            suppliers.replace(s);
        });
    }

    private SupplierEntity createMirrorSupplier(MemberEntity m) {
        SupplierEntity s = new SupplierEntity();
        s.id = idGenerator.newId();
        s.code = m.code; // même code identifiant — facilite la traçabilité
        s.name = m.name;
        s.phone = m.phone;
        s.email = m.email;
        s.cityName = m.village;
        s.contactName = m.name;
        s.notes = "Miroir membre " + m.code + " (auto-créé)";
        // Un producteur déclaré délégué avant la validation de son dossier
        // doit l'être aussi sur le miroir, sinon la case cochée sur sa
        // fiche resterait sans effet.
        s.collector = m.collector;
        s.sectionId = m.collector ? m.sectionId : null;
        s.collectorMarginRate = m.collector ? m.collectorMarginRate : null;
        s.active = true;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        s.createdBy = safeUserId();
        if (suppliers.codeExists(s.code)) {
            // Cas rare : un supplier porte déjà ce code (collision). On
            // suffixe pour préserver l'unicité.
            s.code = s.code + "-MB";
            if (suppliers.codeExists(s.code)) {
                throw new BusinessException("Impossible d'auto-créer le fournisseur miroir : code en collision.");
            }
        }
        suppliers.insert(s);
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
