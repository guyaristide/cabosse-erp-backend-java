package com.ntech.cabosse.settlement.service;

import com.mongodb.client.model.Updates;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.producerpayment.entity.ProducerPaymentBeneficiary;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.settlement.dto.SettlementRequestDto;
import com.ntech.cabosse.settlement.dto.SettlementRequestUpsertDto;
import com.ntech.cabosse.settlement.entity.SettlementRequestEntity;
import com.ntech.cabosse.settlement.entity.SettlementRequestStatus;
import com.ntech.cabosse.settlement.repository.SettlementRequestRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Le circuit d'approbation du règlement d'un solde.
 *
 * <p>Demandé par l'expert-comptable le 12/09/2026. Régler un délégué ou
 * un producteur était un geste unique : la caissière enregistrait le
 * paiement, l'argent sortait, aucune décision n'était demandée ni gardée
 * en trace. La comptable veut solliciter l'accord du président ou du
 * directeur avant la sortie.</p>
 *
 * <p>Le circuit des avances n'est pas dupliqué, il est étendu : même file
 * d'approbation, même échelon de gouvernance au-delà d'un seuil, mêmes
 * droits distincts pour demander, approuver et payer. Deux mécaniques
 * d'approbation finiraient par diverger.</p>
 *
 * <p>Tout ce qui relève de la gouvernance est en réglage, pas en règle :
 * qui est concerné, à partir de quel montant, et à partir de quel autre
 * montant le second échelon devient nécessaire. La réponse appartient à
 * chaque structure, et le logiciel n'a pas à la présumer. Par défaut,
 * personne n'est concerné : rien ne change pour qui n'a rien décidé.</p>
 */
@ApplicationScoped
public class SettlementRequestService {

    @Inject SettlementRequestRepository repo;
    @Inject SettlementRequestRefService refs;
    @Inject IdGenerator idGenerator;
    @Inject PermissionResolver permissions;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject SettlementNotifier notifier;

    // ─── Le réglage ─────────────────────────────────────────────────

    /**
     * Ce bénéficiaire, pour ce montant, doit-il passer par une décision ?
     *
     * <p>Le périmètre d'abord, le seuil ensuite. Hors périmètre, aucun
     * seuil ne s'applique : une structure qui n'a rien décidé continue de
     * régler directement.</p>
     */
    public boolean approvalRequired(ProducerPaymentBeneficiary kind, BigDecimal amount) {
        TenantPreferences prefs = preferencesLookup.current();
        if (prefs == null) return false;
        String scope = prefs.settlementApprovalScope();
        boolean inScope = switch (scope) {
            case TenantPreferences.SETTLEMENT_APPROVAL_ALL -> true;
            case TenantPreferences.SETTLEMENT_APPROVAL_DELEGATES ->
                    kind == ProducerPaymentBeneficiary.DELEGATE;
            case TenantPreferences.SETTLEMENT_APPROVAL_MEMBERS ->
                    kind == ProducerPaymentBeneficiary.MEMBER;
            default -> false;
        };
        if (!inScope) return false;
        BigDecimal threshold = prefs.settlementApprovalThreshold();
        return nz(amount).compareTo(threshold) >= 0;
    }

    /**
     * Le second échelon est-il exigé ?
     *
     * <p>Deux raisons distinctes, et il suffit d'une. Le montant :
     * seuil absent, il n'y a qu'un échelon et qui peut approuver
     * approuve tout. Le distinguer de zéro est délibéré, comme pour les
     * avances : une structure qui écrit zéro veut que tout remonte au
     * conseil, et confondre les deux retournerait son intention.</p>
     *
     * <p>Le moyen de règlement ensuite : le pouvoir de décision n'est
     * pas le même selon l'instrument (expert-comptable, 13/09/2026). Un
     * chèque engage le compte en banque et remonte au président ; une
     * sortie de caisse relève de la direction. Un petit chèque reste un
     * chèque, d'où l'indépendance des deux règles.</p>
     */
    private boolean governanceRequired(BigDecimal amount, PaymentMethod method) {
        TenantPreferences prefs = preferencesLookup.current();
        if (prefs == null) return false;
        BigDecimal threshold = prefs.settlementGovernanceThreshold;
        if (threshold != null && nz(amount).compareTo(threshold) >= 0) return true;
        return method != null && prefs.settlementGovernanceMethods().contains(method.name());
    }

    // ─── Le circuit ─────────────────────────────────────────────────

    public SettlementRequestDto request(SettlementRequestUpsertDto payload) {
        ProducerPaymentBeneficiary kind = payload.delegateSupplierId() != null
                ? ProducerPaymentBeneficiary.DELEGATE : ProducerPaymentBeneficiary.MEMBER;
        if (payload.delegateSupplierId() == null && payload.memberId() == null) {
            throw new BusinessException(Messages.msg("m.set-beneficiary-required"));
        }
        // Une seule demande ouverte par bénéficiaire : deux feraient
        // sortir deux fois le même dû.
        repo.findOpenFor(payload.memberId(), payload.delegateSupplierId())
                .ifPresent(open -> {
                    throw new BusinessException(Messages.msg("m.set-already-open", open.ref));
                });

        SettlementRequestEntity e = new SettlementRequestEntity();
        e.id = idGenerator.newId();
        e.ref = refs.next();
        e.beneficiaryKind = kind;
        e.memberId = payload.memberId();
        e.delegateSupplierId = payload.delegateSupplierId();
        e.beneficiaryName = payload.beneficiaryName();
        e.requestedAmount = payload.amount();
        e.paymentMethod = payload.paymentMethod();
        // Figé à la demande : déplacer le seuil ensuite ne doit pas
        // changer ce qu'une demande déjà déposée exigeait.
        e.governanceApprovalRequired =
                governanceRequired(payload.amount(), payload.paymentMethod());
        e.status = SettlementRequestStatus.PENDING_APPROVAL;
        e.campaignId = payload.campaignId();
        e.siteId = payload.siteId();
        e.notes = payload.notes();
        e.requestedOn = LocalDate.now();
        e.requestedAt = Instant.now();
        e.requestedByEmail = actor();
        e.createdAt = e.requestedAt;
        e.updatedAt = e.requestedAt;
        repo.insert(e);

        // Après l'écriture : une alerte qui part sur une demande que la
        // base n'a pas gardée annoncerait une décision à prendre sur rien.
        notifier.settlementAwaitsApproval(e);

        audit.event(AuditEventType.SETTLEMENT_REQUESTED)
                .actorEmail(actor())
                .target("settlement_request", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Demande de règlement " + e.ref + " pour " + e.beneficiaryName
                        + " : " + e.requestedAmount)
                .record();
        return SettlementRequestDto.from(e);
    }

    public SettlementRequestDto approve(UUID id, BigDecimal approvedAmount, String note) {
        SettlementRequestEntity e = load(id);
        ensureCanDecide(e);
        BigDecimal granted = approvedAmount != null ? approvedAmount : nz(e.requestedAmount);
        if (granted.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.set-approved-amount-positive"));
        }
        if (granted.compareTo(nz(e.requestedAmount)) > 0) {
            throw new BusinessException(Messages.msg("m.set-approved-above-requested",
                    String.valueOf(granted), String.valueOf(e.requestedAmount)));
        }
        boolean moved = repo.transition(id, SettlementRequestStatus.PENDING_APPROVAL,
                SettlementRequestStatus.APPROVED,
                Updates.combine(
                        Updates.set("approvedAmount", granted),
                        Updates.set("decidedAt", Instant.now()),
                        Updates.set("decidedByEmail", actor()),
                        Updates.set("decisionNote", note)));
        if (!moved) {
            throw new BusinessException(Messages.msg("m.set-already-decided", e.ref));
        }
        audit.event(AuditEventType.SETTLEMENT_APPROVED)
                .actorEmail(actor())
                .target("settlement_request", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Règlement " + e.ref + " approuvé pour " + granted)
                .record();
        SettlementRequestEntity decided = load(id);
        // La caisse attend ce feu vert pour sortir l'argent : c'est le
        // second moment où quelqu'un doit être prévenu.
        notifier.settlementApproved(decided, currentUserId());
        return SettlementRequestDto.from(decided);
    }

    public SettlementRequestDto reject(UUID id, String reason) {
        SettlementRequestEntity e = load(id);
        ensureCanDecide(e);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(Messages.msg("m.set-rejection-reason-required"));
        }
        boolean moved = repo.transition(id, SettlementRequestStatus.PENDING_APPROVAL,
                SettlementRequestStatus.REJECTED,
                Updates.combine(
                        Updates.set("decidedAt", Instant.now()),
                        Updates.set("decidedByEmail", actor()),
                        Updates.set("decisionNote", reason)));
        if (!moved) {
            throw new BusinessException(Messages.msg("m.set-already-decided", e.ref));
        }
        audit.event(AuditEventType.SETTLEMENT_REJECTED)
                .actorEmail(actor())
                .target("settlement_request", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Règlement " + e.ref + " refusé : " + reason)
                .record();
        return SettlementRequestDto.from(load(id));
    }

    /**
     * La demande a fait son office : le règlement est enregistré.
     *
     * <p>Appelée par le service de règlement, pas par un écran : c'est le
     * paiement qui solde la demande, et le dire depuis ailleurs laisserait
     * les deux diverger.</p>
     */
    public void markPaid(UUID id, UUID paymentId, String paymentRef) {
        repo.transition(id, SettlementRequestStatus.APPROVED, SettlementRequestStatus.PAID,
                Updates.combine(
                        Updates.set("paymentId", paymentId),
                        Updates.set("paymentRef", paymentRef),
                        Updates.set("paidAt", Instant.now())));
    }

    // ─── Lecture ────────────────────────────────────────────────────

    public List<SettlementRequestDto> search(String status, int skip, int limit) {
        return repo.search(status, skip, limit).stream().map(SettlementRequestDto::from).toList();
    }

    public long countSearch(String status) {
        return repo.countSearch(status);
    }

    public SettlementRequestDto get(UUID id) {
        return SettlementRequestDto.from(load(id));
    }

    /** L'approbation en cours d'un bénéficiaire, pour le règlement. */
    public Optional<SettlementRequestEntity> openFor(UUID memberId, UUID delegateSupplierId) {
        return repo.findOpenFor(memberId, delegateSupplierId);
    }

    public List<SettlementRequestEntity> pending() {
        return repo.findByStatus(SettlementRequestStatus.PENDING_APPROVAL);
    }

    // ─── Petits outils ──────────────────────────────────────────────

    private SettlementRequestEntity load(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.set-not-found")));
    }

    /**
     * Qui tranche. Au-delà du second seuil, le droit ordinaire ne suffit
     * plus : c'est tout l'objet de l'échelon.
     */
    private void ensureCanDecide(SettlementRequestEntity e) {
        if (e.status != SettlementRequestStatus.PENDING_APPROVAL) {
            throw new BusinessException(Messages.msg("m.set-already-decided", e.ref));
        }
        boolean governance = Boolean.TRUE.equals(e.governanceApprovalRequired);
        if (governance && !permissions.can(Permission.COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE)) {
            throw new BusinessException(Messages.msg("m.set-governance-required"));
        }
        if (!governance && !permissions.can(Permission.COLLECTION_SETTLEMENT_APPROVE)
                && !permissions.can(Permission.COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE)) {
            throw new BusinessException(Messages.msg("m.set-approval-right-required"));
        }
    }

    private UUID currentUserId() {
        try {
            return tenantContext.userId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
