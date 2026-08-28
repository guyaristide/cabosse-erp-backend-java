package com.ntech.cabosse.membercredit.service;

import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberStatus;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.membercredit.dto.CreateMemberCreditDto;
import com.ntech.cabosse.membercredit.dto.DisburseMemberCreditDto;
import com.ntech.cabosse.membercredit.dto.MemberCreditResponseDto;
import com.ntech.cabosse.membercredit.dto.MemberDebtDto;
import com.ntech.cabosse.membercredit.entity.MemberCreditEntity;
import com.ntech.cabosse.membercredit.entity.MemberCreditKind;
import com.ntech.cabosse.membercredit.entity.MemberCreditStatus;
import com.ntech.cabosse.membercredit.repository.MemberCreditRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ForbiddenException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crédits et avances aux producteurs membres.
 *
 * <p>Le circuit reproduit celui de la coopérative : la demande est
 * appréciée, approuvée par l'échelon que le montant impose, puis décaissée.
 * Un seuil paramétrable sépare les deux cas, parce que faire remonter au
 * conseil une avance de carburant paralyserait le terrain, et qu'accorder
 * seul un financement de moto ferait sauter le contrôle.</p>
 *
 * <p>Le remboursement n'est pas géré ici : il se fait par retenue sur les
 * livraisons, décidée au moment de payer le producteur
 * ({@code ProducerPurchaseService}).</p>
 */
@ApplicationScoped
public class MemberCreditService {

    @Inject MemberCreditRepository repo;
    @Inject MemberCreditRefService refService;
    @Inject MemberRepository members;
    @Inject SectionRepository sections;
    @Inject CampaignResolver campaignResolver;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;
    @Inject PermissionResolver permissions;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.shared.storage.AttachmentService attachments;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<MemberCreditResponseDto> page(UUID memberId, String status, UUID campaignId,
                                                    PageRequest pr) {
        long total = repo.countSearch(memberId, status, campaignId);
        List<MemberCreditResponseDto> items = repo
                .search(memberId, status, campaignId, pr.skip(), pr.perPage())
                .stream().map(MemberCreditResponseDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (memberId != null) filters.put("memberId", memberId.toString());
        if (status != null && !status.isBlank()) filters.put("status", status);
        if (campaignId != null) filters.put("campaignId", campaignId.toString());
        return Pagination.of(total, pr, new String[]{"requestedAt"}, "desc", filters, items);
    }


    /** Toutes les lignes du filtre courant, pour l'export. */
    public List<MemberCreditResponseDto> listForExport(UUID memberId, String status, UUID campaignId) {
        return repo.search(memberId, status, campaignId, 0, Integer.MAX_VALUE)
                .stream().map(MemberCreditResponseDto::from).toList();
    }

    public MemberCreditResponseDto getById(UUID id) {
        return MemberCreditResponseDto.from(loadOrFail(id));
    }

    /** Ce que le producteur doit encore, engagement par engagement. */
    public MemberDebtDto debtOf(UUID memberId) {
        MemberEntity m = members.findById(memberId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.mcr-producer-not-found", memberId)));
        List<MemberCreditEntity> outstanding = repo.outstandingForMember(memberId);
        BigDecimal total = BigDecimal.ZERO;
        List<MemberDebtDto.Line> lines = new java.util.ArrayList<>();
        for (MemberCreditEntity c : outstanding) {
            BigDecimal remaining = nz(c.remainingFcfa);
            if (remaining.signum() <= 0) continue;
            total = total.add(remaining);
            lines.add(new MemberDebtDto.Line(
                    c.id, c.ref, c.kind != null ? c.kind.name() : null, c.purpose,
                    c.disbursedAt, nz(c.amountFcfa), nz(c.imputedAmountFcfa), remaining));
        }
        return new MemberDebtDto(m.id, m.name, total, lines);
    }

    // ─── Cycle de vie ───────────────────────────────────────────────

    public MemberCreditResponseDto create(CreateMemberCreditDto p) {
        MemberEntity m = members.findById(p.memberId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.mcr-producer-not-found", p.memberId())));
        if (m.status == MemberStatus.RETIRED) {
            throw new BusinessException(Messages.msg("m.mcr-member-retired", m.name));
        }
        TenantPreferences prefs = preferences.current();
        BigDecimal threshold = prefs.memberCreditApprovalThresholdFcfa();

        MemberCreditEntity e = new MemberCreditEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.kind = p.kind();
        e.memberId = m.id;
        e.memberName = m.name;
        e.memberCode = m.code;
        e.sectionId = m.sectionId;
        e.sectionName = m.sectionId != null
                ? sections.findById(m.sectionId).map(s -> s.name).orElse(null) : null;

        CampaignEntity campaign =
                campaignResolver.resolveOptionalForDate(p.requestedAt(), p.campaignId());
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignLabel = campaign != null ? campaign.label : null;

        e.purpose = blankToNull(p.purpose());
        e.amountFcfa = p.amountFcfa();
        e.requestedAt = p.requestedAt() != null ? p.requestedAt() : LocalDate.now();
        e.requestedByEmail = actor();
        e.notes = blankToNull(p.notes());

        // Le seuil est figé à la demande : le relever ensuite ne doit pas
        // effacer l'exigence qui pesait sur un dossier déjà déposé.
        e.governanceApprovalRequired = threshold.signum() > 0
                && p.amountFcfa().compareTo(threshold) >= 0;
        e.status = MemberCreditStatus.PENDING_APPROVAL;

        e.imputedAmountFcfa = BigDecimal.ZERO;
        e.remainingFcfa = p.amountFcfa();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        repo.insert(e);

        audit(e, AuditEventType.MEMBER_CREDIT_REQUESTED,
                "Demande " + e.ref + " : " + e.amountFcfa + " pour " + e.memberName);
        return MemberCreditResponseDto.from(e);
    }

    public MemberCreditResponseDto approve(UUID id, String note) {
        MemberCreditEntity e = loadOrFail(id);
        if (e.status != MemberCreditStatus.PENDING_APPROVAL) {
            throw new BusinessException(Messages.msg("m.mcr-approve-requires-pending", e.status));
        }
        // Séparation des tâches : celui qui a déposé le dossier ne peut
        // pas le trancher. L'administrateur du tenant en est exempt, pour
        // qu'une structure à compte unique reste opérable.
        if (isSameActor(e.createdBy, e.requestedByEmail) && !permissions.currentIsTenantAdmin()) {
            throw new BusinessException(Messages.msg("m.mcr-approve-self-forbidden", e.ref));
        }
        if (e.governanceApprovalRequired
                && !permissions.currentIsTenantAdmin()
                && !permissions.can(Permission.MEMBER_CREDIT_APPROVE_GOVERNANCE)) {
            throw new ForbiddenException(
                    Messages.msg("m.mcr-governance-approval-required", e.ref));
        }
        e.status = MemberCreditStatus.APPROVED;
        e.approvedAt = Instant.now();
        e.approvedBy = safeUserId();
        e.approvedByEmail = actor();
        e.approvalNote = blankToNull(note);
        e.updatedAt = e.approvedAt;
        repo.replace(e);

        audit(e, AuditEventType.MEMBER_CREDIT_APPROVED,
                "Approbation " + e.ref + " (" + e.amountFcfa + ")");
        return MemberCreditResponseDto.from(e);
    }

    public MemberCreditResponseDto reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(Messages.msg("m.mcr-rejection-reason-required"));
        }
        MemberCreditEntity e = loadOrFail(id);
        if (e.status != MemberCreditStatus.PENDING_APPROVAL) {
            throw new BusinessException(Messages.msg("m.mcr-reject-requires-pending", e.status));
        }
        e.status = MemberCreditStatus.REJECTED;
        e.rejectedAt = Instant.now();
        e.rejectedByEmail = actor();
        e.rejectionReason = reason.trim();
        e.updatedAt = e.rejectedAt;
        repo.replace(e);

        audit(e, AuditEventType.MEMBER_CREDIT_REJECTED, "Refus " + e.ref + " : " + e.rejectionReason);
        return MemberCreditResponseDto.from(e);
    }

    /**
     * Remise des fonds. C'est le décaissement qui rend l'engagement
     * imputable : tant que l'argent n'est pas sorti, il n'y a rien à
     * retenir sur une livraison.
     */
    public MemberCreditResponseDto disburse(UUID id, DisburseMemberCreditDto p) {
        MemberCreditEntity e = loadOrFail(id);
        if (e.status != MemberCreditStatus.APPROVED) {
            throw new BusinessException(Messages.msg("m.mcr-disburse-requires-approved", e.status));
        }
        // Deux paires d'yeux par transition : l'approbateur ne remet pas
        // lui-même les fonds. Même exemption que ci-dessus pour l'admin.
        if (isSameActor(e.approvedBy, e.approvedByEmail) && !permissions.currentIsTenantAdmin()) {
            throw new BusinessException(Messages.msg("m.mcr-disburse-self-forbidden", e.ref));
        }
        LocalDate date = p.disbursedAt() != null ? p.disbursedAt() : LocalDate.now();
        e.status = MemberCreditStatus.DISBURSED;
        e.disbursedAt = date;
        e.paymentMethod = p.paymentMethod();
        e.paymentRef = blankToNull(p.paymentRef());
        e.updatedAt = Instant.now();

        accounting.postFromMemberCredit(
                        e.id, e.ref, e.memberName,
                        preferences.current().memberCreditAccount(),
                        accounting.treasuryAccountFor(p.paymentMethod()),
                        nz(e.amountFcfa), date)
                .ifPresent(piece -> e.pieceRef = piece.ref);

        repo.replace(e);
        audit(e, AuditEventType.MEMBER_CREDIT_DISBURSED,
                "Décaissement " + e.ref + " : " + e.amountFcfa + " à " + e.memberName);
        return MemberCreditResponseDto.from(e);
    }

    /**
     * Abandon d'un engagement. Après décaissement, le solde restant est
     * abandonné par décision de gestion : la créance disparaît des
     * retenues à venir mais la trace demeure.
     */
    public MemberCreditResponseDto cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(Messages.msg("m.mcr-reason-required"));
        }
        MemberCreditEntity e = loadOrFail(id);
        if (e.status == MemberCreditStatus.SETTLED || e.status == MemberCreditStatus.CANCELLED
                || e.status == MemberCreditStatus.REJECTED) {
            throw new BusinessException(Messages.msg("m.mcr-already-closed", e.status));
        }
        e.status = MemberCreditStatus.CANCELLED;
        e.notes = (e.notes == null ? "" : e.notes + " | ") + "Abandon : " + reason.trim();
        e.updatedAt = Instant.now();
        repo.replace(e);
        audit(e, AuditEventType.MEMBER_CREDIT_CANCELLED, "Abandon " + e.ref + " : " + reason.trim());
        return MemberCreditResponseDto.from(e);
    }

    // ─── Retenue sur livraison (appelée par l'achat producteur) ──────

    /**
     * Applique une retenue décidée sur une livraison. Atomique : deux
     * livraisons concurrentes ne peuvent pas retenir plus que le solde.
     *
     * @return le montant effectivement retenu
     * @throws BusinessException si l'engagement n'est pas imputable ou si
     *         le solde ne couvre pas la retenue demandée
     */
    public MemberCreditEntity impute(UUID creditId, BigDecimal amount, UUID memberId) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.mcr-withholding-amount-positive"));
        }
        MemberCreditEntity e = loadOrFail(creditId);
        if (memberId != null && !memberId.equals(e.memberId)) {
            throw new BusinessException(Messages.msg("m.mcr-credit-wrong-member", e.ref));
        }
        if (e.status != MemberCreditStatus.DISBURSED) {
            throw new BusinessException(Messages.msg("m.mcr-credit-not-imputable", e.ref, e.status));
        }
        if (!repo.tryImpute(creditId, amount)) {
            throw new BusinessException(Messages.msg("m.mcr-withholding-exceeds-remaining",
                    amount, e.ref, nz(e.remainingFcfa)));
        }
        return e;
    }

    /** Journalise la retenue une fois la livraison enregistrée. */
    public void recordImputation(MemberCreditEntity credit, UUID purchaseId, String purchaseRef,
                                 LocalDate date, BigDecimal amount, String notes) {
        MemberCreditEntity.Imputation imputation = new MemberCreditEntity.Imputation();
        imputation.id = idGenerator.newId();
        imputation.purchaseId = purchaseId;
        imputation.purchaseRef = purchaseRef;
        imputation.date = date;
        imputation.amountFcfa = amount;
        imputation.decidedByEmail = actor();
        imputation.decidedAt = Instant.now();
        imputation.notes = blankToNull(notes);

        boolean settled = nz(credit.remainingFcfa).subtract(amount).signum() <= 0;
        repo.appendImputation(credit.id, imputation, settled);

        audit(credit, AuditEventType.MEMBER_CREDIT_IMPUTED,
                "Retenue de " + amount + " sur " + purchaseRef + " au titre de " + credit.ref);
    }

    /** Crédits portant une retenue au titre d'un reçu, pour la défaire. */
    public java.util.List<MemberCreditEntity> findImputedByPurchase(UUID purchaseId) {
        return repo.findImputedByPurchase(purchaseId);
    }

    /**
     * Défait une retenue au titre d'un reçu annulé : le solde revient au
     * membre, la ligne quitte le journal du crédit, et un crédit soldé par
     * cette seule retenue se rouvre.
     */
    public void reverseImputation(UUID creditId, UUID purchaseId, BigDecimal amount) {
        if (creditId == null || amount == null || amount.signum() <= 0) return;
        repo.creditBack(creditId, amount);
        repo.removeImputationForPurchase(creditId, purchaseId);
        MemberCreditEntity credit = repo.findById(creditId).orElse(null);
        if (credit != null) {
            audit(credit, AuditEventType.MEMBER_CREDIT_IMPUTED,
                    "Retenue de " + amount + " annulée : le reçu a été contre-passé");
        }
    }

    public void creditBack(UUID creditId, BigDecimal amount) {
        repo.creditBack(creditId, amount);
    }

    // ─── Helpers ────────────────────────────────────────────────────


    // ─── Pièces jointes ─────────────────────────────────────────────

    /**
     * Dépose une pièce justificative. Le fichier part au stockage avant
     * d'être référencé : rien n'apparaît dans la liste qui ne soit
     * réellement consultable.
     */
    public MemberCreditResponseDto attach(java.util.UUID id, byte[] bytes,
                                          String mimeType, String fileName, String label) {
        MemberCreditEntity e = loadOrFail(id);
        var ref = attachments.store(bytes, mimeType, fileName, label, e.id, "member_credit");
        repo.pushAttachment(e.id, ref);
        audit.event(AuditEventType.MEMBER_CREDIT_ATTACHMENT)
                .actorEmail(actor())
                .target("member_credit", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » ajoutée sur " + e.ref)
                .record();
        return MemberCreditResponseDto.from(loadOrFail(id));
    }

    /** Retire une pièce et archive son binaire. */
    public MemberCreditResponseDto detach(java.util.UUID id, java.util.UUID fileId) {
        MemberCreditEntity e = loadOrFail(id);
        var ref = attachments.find(e.attachments, fileId);
        repo.pullAttachment(e.id, fileId);
        attachments.discard(ref);
        audit.event(AuditEventType.MEMBER_CREDIT_ATTACHMENT)
                .actorEmail(actor())
                .target("member_credit", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » retirée de " + e.ref)
                .record();
        return MemberCreditResponseDto.from(loadOrFail(id));
    }

    /** Contenu d'une pièce, servi en téléchargement. */
    public com.ntech.cabosse.shared.storage.AttachmentService.AttachmentStream openAttachment(
            java.util.UUID id, java.util.UUID fileId) {
        return attachments.open(loadOrFail(id).attachments, fileId);
    }

    /**
     * L'utilisateur courant est-il celui qui a agi sur le dossier ?
     * L'identifiant tranche quand les deux sont connus ; l'adresse ne sert
     * que de secours pour les dossiers antérieurs sans identifiant.
     */
    private boolean isSameActor(UUID otherId, String otherEmail) {
        UUID me = safeUserId();
        if (me != null && otherId != null) return me.equals(otherId);
        String email = actor();
        return email != null && otherEmail != null && email.equalsIgnoreCase(otherEmail);
    }

    private MemberCreditEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.mcr-credit-not-found", id)));
    }

    private void audit(MemberCreditEntity e, AuditEventType type, String description) {
        audit.event(type)
                .actorEmail(actor())
                .target("member_credit", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .record();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Utilitaire de lecture pour les services appelants. */
    public MemberCreditKind kindOf(UUID creditId) {
        return loadOrFail(creditId).kind;
    }
}
