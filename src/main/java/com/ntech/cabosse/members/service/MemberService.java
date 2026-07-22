package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.dto.MemberExternalCodeDto;
import com.ntech.cabosse.members.dto.MemberResponseDto;
import com.ntech.cabosse.members.dto.MemberUpsertDto;
import com.ntech.cabosse.members.entity.MemberEntity;
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
        List<MemberResponseDto> items = members.search(q, statusFilter, pr.skip(), pr.perPage())
                .stream()
                .map(MemberResponseDto::from)
                .toList();
        Map<String, String> filters = new HashMap<>();
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        if (statusFilter != null) filters.put("status", statusFilter.name());
        return Pagination.of(total, pr, new String[]{"name"}, "asc", filters, items);
    }

    public MemberResponseDto getById(UUID id) {
        return MemberResponseDto.from(loadOrFail(id));
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
        return MemberResponseDto.from(e);
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
        return MemberResponseDto.from(e);
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
        return MemberResponseDto.from(e);
    }

    // ─── Update ─────────────────────────────────────────────────────

    public MemberResponseDto update(UUID id, MemberUpsertDto payload) {
        MemberEntity e = loadOrFail(id);
        if (payload.status() == MemberStatus.RETIRED && e.status != MemberStatus.RETIRED) {
            throw new BusinessException(
                    "La radiation passe par l'action dédiée (remboursement des parts et clôture).");
        }
        applyPayload(e, payload);
        e.updatedAt = Instant.now();
        members.replace(e);

        // Synchroniser le supplier miroir (nom + contact).
        if (e.supplierId != null) {
            suppliers.findById(e.supplierId).ifPresent(s -> {
                s.name = e.name;
                s.phone = e.phone;
                s.email = e.email;
                s.cityName = e.village;
                s.updatedAt = Instant.now();
                suppliers.replace(s);
            });
        }
        return MemberResponseDto.from(e);
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
        return MemberResponseDto.from(e);
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
        e.civilStatus = p.civilStatus();
        e.birthDate = p.birthDate();
        e.birthYear = p.birthYear();
        e.idDocType = blankToNull(p.idDocType());
        e.idDocNumber = blankToNull(p.idDocNumber());
        e.idCardFileId = p.idCardFileId();
        e.sectionId = p.sectionId();
        e.followUpAgentMemberId = p.followUpAgentMemberId();
        e.deliveredProductCodes = p.deliveredProductCodes() == null ? new ArrayList<>()
                : p.deliveredProductCodes().stream()
                        .filter(c -> c != null && !c.isBlank())
                        .map(String::trim).distinct().collect(Collectors.toList());
        e.externalProducerCodes = p.externalProducerCodes() == null ? new ArrayList<>()
                : p.externalProducerCodes().stream()
                        .filter(x -> x != null && x.type() != null && !x.type().isBlank())
                        .map(MemberExternalCodeDto::toEntity).collect(Collectors.toList());
        e.village = blankToNull(p.village());
        e.phone = blankToNull(p.phone());
        e.email = blankToNull(p.email());
        e.joinedAt = p.joinedAt();
        e.partsSocialesAmount = p.partsSocialesAmount();
        e.status = p.status();
        e.preferredPaymentMethod = blankToNull(p.preferredPaymentMethod());
        e.mobileMoneyNumber = blankToNull(p.mobileMoneyNumber());
        e.notes = blankToNull(p.notes());
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
