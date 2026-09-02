package com.ntech.cabosse.collector.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.collector.dto.ApproveAdvanceDto;
import com.ntech.cabosse.collector.dto.CollectorAdvanceResponseDto;
import com.ntech.cabosse.collector.dto.DelegateTermsDto;
import com.ntech.cabosse.collector.dto.CreateAdvanceDto;
import com.ntech.cabosse.collector.dto.DisburseAdvanceDto;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.repository.SectionRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Avances aux délégués collecteurs (backlog ACH-02) : versement des fonds,
 * puis clôture au décompte de campagne. Chaque étape produit ses écritures.
 *
 * <p>L'apurement ne se saisit pas ici. La matière n'entre que par les reçus
 * d'achat producteur, seuls à porter l'origine de chaque kilo : une
 * livraison en bloc sans détail producteur romprait la traçabilité, que la
 * vente exige. Ce sont donc les reçus rattachés au délégué qui décrémentent
 * son compte, et {@link DelegateAccountService} en donne la lecture.</p>
 */
@ApplicationScoped
public class CollectorAdvanceService {

    /**
     * Unité dans laquelle s'exprime la contrepartie attendue.
     *
     * <p>Elle suit le barème, qui s'exprime au kilo. Portée par la donnée
     * plutôt qu'écrite dans l'affichage : le jour où un barème se compte
     * au sac ou à la tonne, les demandes déjà déposées gardent l'unité
     * dans laquelle elles ont été convenues.</p>
     */
    static final String COUNTERPART_UNIT = "kg";

    @Inject CollectorAdvanceRepository repo;
    @Inject DelegateAccountService delegateAccount;
    @Inject CollectorAdvanceRefService refService;
    @Inject SupplierRepository suppliers;
    @Inject CampaignResolver campaignResolver;
    @Inject SectionRepository sections;
    @Inject ArticleRepository articles;
    @Inject StockService stockService;
    @Inject AccountingService accounting;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject TenantContext tenantContext;
    @Inject AdvanceNotifier notifier;
    @Inject AuditService audit;
    @Inject com.ntech.cabosse.permission.service.PermissionResolver permissions;
    @Inject JsonWebToken jwt;
    @Inject com.ntech.cabosse.shared.storage.AttachmentService attachments;
    @Inject com.ntech.cabosse.shared.security.CurrentActor currentActor;

    // ─── Lecture ────────────────────────────────────────────────────

    public long countSearch(String status) { return repo.countSearch(status); }

    public List<CollectorAdvanceResponseDto> search(String status, int skip, int limit) {
        return repo.search(status, skip, limit).stream()
                .map(CollectorAdvanceResponseDto::from).toList();
    }

    public CollectorAdvanceResponseDto getById(UUID id) {
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    // ─── Cycle ──────────────────────────────────────────────────────

    public CollectorAdvanceResponseDto create(CreateAdvanceDto p, UUID siteId) {
        SupplierEntity delegate = suppliers.findById(p.delegateSupplierId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-delegate-not-found", p.delegateSupplierId())));
        if (!delegate.collector) {
            throw new BusinessException(Messages.msg("m.col-not-a-delegate-check-card", delegate.name));
        }
        CollectorAdvanceEntity e = new CollectorAdvanceEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.ref = refService.next();
        e.delegateSupplierId = delegate.id;
        e.delegateName = delegate.name;
        e.sectionId = delegate.sectionId;
        if (delegate.sectionId != null) {
            sections.findById(delegate.sectionId).ifPresent(s -> e.sectionName = s.name);
        }
        // La campagne rattache l'avance sans la conditionner : une avance
        // consentie avant l'ouverture d'une campagne reste valable.
        CampaignEntity campaign =
                campaignResolver.resolveOptionalForDate(p.advanceDate(), p.campaignId());
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        // Ce que le délégué traîne d'une campagne antérieure, et l'absence
        // éventuelle de mise en compte, s'affichent sur l'écran de demande.
        // Le logiciel ne refuse plus : refinancer un délégué endetté est
        // une décision de la gouvernance, et un blocage automatique se
        // substituerait à elle.

        e.siteId = siteId;
        e.advanceDate = p.advanceDate();
        e.advanceAmountFcfa = p.advanceAmountFcfa();
        e.paymentMethod = p.paymentMethod();
        e.consumedAmountFcfa = BigDecimal.ZERO;
        e.remainingFcfa = p.advanceAmountFcfa();
        // Une demande, pas un versement : rien n'est sorti tant qu'elle
        // n'est pas approuvée puis décaissée.
        e.status = CollectorAdvanceStatus.PENDING_APPROVAL;

        // Le seuil est figé ici, comme côté crédit producteur : le relever
        // ensuite ne doit pas dispenser d'approbation une demande déjà
        // déposée. Zéro signifie que la gouvernance se prononce sur tout.
        BigDecimal threshold =
                preferencesLookup.current().collectorAdvanceApprovalThresholdFcfa();
        e.governanceApprovalRequired = threshold.signum() > 0
                && p.advanceAmountFcfa().compareTo(threshold) >= 0;

        // La contrepartie attendue, au barème du jour de la demande. Elle
        // passe par la même formule que la fiche technique : deux
        // divisions écrites deux fois finiraient par diverger.
        DelegateTermsDto terms = delegateAccount.terms(
                delegate.id, e.campaignId, null, p.advanceAmountFcfa());
        e.expectedQuantity = terms.suggestedVolumeKg();
        e.counterpartUnitPriceFcfa = terms.scalePricePerKgFcfa();
        e.expectedQuantityUnit = e.expectedQuantity != null ? COUNTERPART_UNIT : null;

        e.notes = (p.notes() == null || p.notes().isBlank()) ? null : p.notes().trim();
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // L'écriture ne part plus ici : elle suit le décaissement. Une
        // demande refusée ne doit rien laisser au journal.
        repo.insert(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_CREATED,
                "Demande d'avance " + e.advanceAmountFcfa + " pour le délégué " + e.delegateName);
        // Après l'enregistrement, et sans pouvoir le remettre en cause :
        // une demande écrite dont l'alerte n'est pas partie reste une
        // demande écrite.
        notifier.advanceAwaitsApproval(e);
        return CollectorAdvanceResponseDto.from(e);
    }

    /**
     * Approuve une demande d'avance.
     *
     * <p>La contrepartie exigée sur une dette antérieure est
     * <strong>revérifiée</strong> : entre la demande et la décision, le
     * délégué a pu livrer, ou sa fiche changer. Approuver sur une
     * situation périmée reviendrait à approuver autre chose que ce qu'on
     * croit.</p>
     */
    public CollectorAdvanceResponseDto approve(UUID id) {
        return approve(id, null);
    }

    /**
     * Approuve, en accordant éventuellement moins que ce qui est demandé.
     *
     * <p>L'approbation n'est plus binaire : la gouvernance peut suivre en
     * partie. Le montant accordé devient celui qui commande tout ce qui
     * suit, les fonds remis, l'écriture, le compte courant du délégué et
     * l'imputation des livraisons. Un montant approuvé que le reste du
     * logiciel ignorerait serait pire que son absence.</p>
     *
     * <p>La contrepartie attendue ne bouge pas, elle. C'est une prévision
     * portée par la demande, pas un engagement recalculé à chaque
     * décision : le point se fait au retour du délégué, sur ce qu'il a
     * effectivement ramené (registre, DEC-29).</p>
     */
    public CollectorAdvanceResponseDto approve(UUID id, ApproveAdvanceDto payload) {
        CollectorAdvanceEntity e = loadOrFail(id);
        requireStatus(e, CollectorAdvanceStatus.PENDING_APPROVAL);
        // Séparation des tâches : celui qui a déposé la demande ne la
        // tranche pas. L'administrateur du tenant en est exempt, pour
        // qu'une structure à compte unique reste opérable.
        if (isSameActor(e.createdBy, e.createdByEmail) && !permissions.currentIsTenantAdmin()) {
            throw new BusinessException(Messages.msg("m.col-approve-self-forbidden", e.ref));
        }
        // Le solde antérieur du délégué et sa mise en compte sont sous les
        // yeux de celui qui approuve, sur la fiche technique. Ils ne
        // barrent plus la route : approuver malgré une dette est une
        // décision, et c'est à l'approbateur de la prendre.

        // Au-dessus du seuil, l'approbation ordinaire ne suffit plus.
        if (e.governanceApprovalRequired
                && !permissions.currentIsTenantAdmin()
                && !permissions.can(Permission.COLLECTION_ADVANCE_APPROVE_GOVERNANCE)) {
            throw new jakarta.ws.rs.ForbiddenException(
                    Messages.msg("m.col-governance-approval-required", e.ref));
        }

        BigDecimal approved = payload != null && payload.approvedAmountFcfa() != null
                ? payload.approvedAmountFcfa() : e.advanceAmountFcfa;
        // Accorder plus que demandé créerait un engagement que personne
        // n'a sollicité.
        if (approved.compareTo(e.advanceAmountFcfa) > 0) {
            throw new BusinessException(Messages.msg(
                    "m.col-approved-above-requested", e.advanceAmountFcfa));
        }
        // Zéro n'est pas une approbation partielle : c'est un refus, et un
        // refus a son motif. L'enregistrer comme une approbation à zéro
        // laisserait au dossier une décision sans raison.
        if (approved.signum() <= 0) {
            throw new BusinessException(Messages.msg("m.col-approved-zero-is-a-refusal"));
        }

        Instant now = Instant.now();
        e.status = CollectorAdvanceStatus.APPROVED;
        e.approvedAmountFcfa = approved;
        e.approvalNote = payload != null && payload.note() != null && !payload.note().isBlank()
                ? payload.note().trim() : null;
        // Rien n'a encore été livré : le reste à couvrir suit le montant
        // accordé, faute de quoi une avance réduite s'imputerait sur le
        // montant demandé.
        e.remainingFcfa = approved;
        e.approvedAt = now;
        e.approvedBy = safeUserId();
        e.approvedByEmail = actor();
        e.updatedAt = now;
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_APPROVED,
                "Approbation " + e.ref + " (" + approved + " sur "
                        + e.advanceAmountFcfa + " demandés) pour " + e.delegateName);
        return CollectorAdvanceResponseDto.from(e);
    }

    /**
     * Refuse une demande, avec son motif.
     *
     * <p>Le motif est exigé et reste au dossier : un refus sans raison ne
     * se conteste pas, et le demandeur irait la chercher de vive voix.</p>
     */
    public CollectorAdvanceResponseDto reject(UUID id, String reason) {
        CollectorAdvanceEntity e = loadOrFail(id);
        requireStatus(e, CollectorAdvanceStatus.PENDING_APPROVAL);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(Messages.msg("m.col-rejection-reason-required"));
        }
        Instant now = Instant.now();
        e.status = CollectorAdvanceStatus.REJECTED;
        e.rejectionReason = reason.trim();
        e.rejectedAt = now;
        e.rejectedBy = safeUserId();
        e.rejectedByEmail = actor();
        e.updatedAt = now;
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_REJECTED,
                "Refus " + e.ref + " : " + e.rejectionReason);
        return CollectorAdvanceResponseDto.from(e);
    }

    /**
     * Décaisse une avance approuvée : les fonds partent, l'écriture passe.
     *
     * <p>C'est ici, et seulement ici, que l'avance devient imputable : les
     * livraisons d'un délégué ne se comptent que sur une avance ouverte.</p>
     */
    public CollectorAdvanceResponseDto disburse(UUID id) {
        return disburse(id, null);
    }

    /**
     * Décaisse en désignant d'où sort l'argent, sous quelle référence et
     * à quel coût.
     *
     * <p>Ces trois informations n'existent pas à la demande d'avance :
     * personne n'y sait encore de quel compte l'argent sortira, ni quel
     * numéro portera le chèque, ni ce que la banque prélèvera. Elles
     * appartiennent au geste qui sort les fonds.</p>
     */
    public CollectorAdvanceResponseDto disburse(UUID id, DisburseAdvanceDto payload) {
        CollectorAdvanceEntity e = loadOrFail(id);
        requireStatus(e, CollectorAdvanceStatus.APPROVED);
        // Deux paires d'yeux par transition : l'approbateur ne sort pas
        // lui-même les fonds. Même exemption pour l'administrateur.
        if (isSameActor(e.approvedBy, e.approvedByEmail) && !permissions.currentIsTenantAdmin()) {
            throw new BusinessException(Messages.msg("m.col-disburse-self-forbidden", e.ref));
        }

        Instant now = Instant.now();
        if (payload != null) {
            // Le moyen prévu à la demande était une intention ; celui du
            // décaissement est ce qui s'est réellement passé, et c'est lui
            // qui oriente l'écriture vers la banque ou la caisse.
            if (payload.paymentMethod() != null) e.paymentMethod = payload.paymentMethod();
            e.bankAccountId = payload.bankAccountId();
            e.paymentRef = (payload.paymentRef() == null || payload.paymentRef().isBlank())
                    ? null : payload.paymentRef().trim();
            // Zéro et « pas de frais » se valent : on ne garde que ce qui
            // pèse, pour qu'un état ne montre pas des lignes à 0 FCFA.
            e.bankFeesFcfa = (payload.bankFeesFcfa() != null
                    && payload.bankFeesFcfa().signum() > 0) ? payload.bankFeesFcfa() : null;
        }
        // Le montant approuvé, jamais celui demandé : c'est lui qui sort
        // de la caisse et qui doit se retrouver au journal.
        accounting.postFromCollectorAdvance(e.id, e.ref, e.delegateName,
                        e.effectiveAmountFcfa(), e.paymentMethod,
                        e.bankAccountId, e.bankFeesFcfa, e.advanceDate)
                .ifPresent(piece -> e.pieceRef = piece.ref);
        e.status = CollectorAdvanceStatus.OPEN;
        e.disbursedAt = now;
        e.disbursedBy = safeUserId();
        e.disbursedByEmail = actor();
        // Le nom est figé ici : l'état de suivi nomme la caissière, et le
        // résoudre après coup échoue dès que le compte est désactivé.
        e.disbursedByName = currentActor.name();
        e.updatedAt = now;
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_DISBURSED,
                "Décaissement " + e.ref + " : " + e.effectiveAmountFcfa()
                        + " à " + e.delegateName);
        return CollectorAdvanceResponseDto.from(e);
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

    /** Un geste ne vaut que depuis l'état qui l'appelle. */
    private static void requireStatus(CollectorAdvanceEntity e, CollectorAdvanceStatus expected) {
        if (e.status != expected) {
            throw new BusinessException(Messages.msg(
                    "m.col-advance-wrong-status", e.ref, e.status.name(), expected.name()));
        }
    }

    /**
     * Clôture au décompte de campagne.
     *
     * <p>Ne se clôt que ce qui a été décaissé : une demande en attente se
     * refuse, elle ne se clôt pas, et clore un refus effacerait son motif
     * derrière un état qui laisse croire à un décompte.</p>
     */
    public CollectorAdvanceResponseDto close(UUID id, String note) {
        CollectorAdvanceEntity e = loadOrFail(id);
        if (e.status == CollectorAdvanceStatus.CLOSED) {
            throw new BusinessException(Messages.msg("m.col-advance-already-closed"));
        }
        requireStatus(e, CollectorAdvanceStatus.OPEN);
        e.status = CollectorAdvanceStatus.CLOSED;
        e.closedAt = Instant.now();
        e.updatedAt = e.closedAt;
        if (note != null && !note.isBlank()) {
            e.notes = (e.notes == null ? "" : e.notes + " · ") + note.trim();
        }
        repo.replace(e);
        audit(e, AuditEventType.COLLECTOR_ADVANCE_CLOSED,
                "Clôture — solde résiduel " + e.remainingFcfa);
        return CollectorAdvanceResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private static ArticleType parseType(String type) {
        try { return ArticleType.valueOf(type); }
        catch (Exception ex) { return ArticleType.RAW_MATERIAL; }
    }

    private void audit(CollectorAdvanceEntity e, AuditEventType type, String desc) {
        audit.event(type)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(desc + " — avance " + e.ref)
                .record();
    }


    // ─── Pièces jointes ─────────────────────────────────────────────

    /**
     * Dépose une pièce justificative. Le fichier part au stockage avant
     * d'être référencé : rien n'apparaît dans la liste qui ne soit
     * réellement consultable.
     */
    public CollectorAdvanceResponseDto attach(java.util.UUID id, byte[] bytes,
                                              String mimeType, String fileName, String label) {
        CollectorAdvanceEntity e = loadOrFail(id);
        var ref = attachments.store(bytes, mimeType, fileName, label, e.id, "collector_advance");
        repo.pushAttachment(e.id, ref);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_ATTACHMENT)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » ajoutée sur " + e.ref)
                .record();
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    /** Retire une pièce et archive son binaire. */
    public CollectorAdvanceResponseDto detach(java.util.UUID id, java.util.UUID fileId) {
        CollectorAdvanceEntity e = loadOrFail(id);
        var ref = attachments.find(e.attachments, fileId);
        repo.pullAttachment(e.id, fileId);
        attachments.discard(ref);
        audit.event(AuditEventType.COLLECTOR_ADVANCE_ATTACHMENT)
                .actorEmail(actor())
                .target("collector_advance", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » retirée de " + e.ref)
                .record();
        return CollectorAdvanceResponseDto.from(loadOrFail(id));
    }

    /** Contenu d'une pièce, servi en téléchargement. */
    public com.ntech.cabosse.shared.storage.AttachmentService.AttachmentStream openAttachment(
            java.util.UUID id, java.util.UUID fileId) {
        return attachments.open(loadOrFail(id).attachments, fileId);
    }

    private CollectorAdvanceEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.col-advance-not-found", id)));
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

}
