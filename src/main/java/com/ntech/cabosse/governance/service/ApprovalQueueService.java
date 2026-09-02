package com.ntech.cabosse.governance.service;

import com.ntech.cabosse.collector.entity.CollectorAdvanceStatus;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.collector.service.DelegateAccountService;
import com.ntech.cabosse.governance.dto.ApprovalDtos.ApprovalKind;
import com.ntech.cabosse.governance.dto.ApprovalDtos.ApprovalQueueDto;
import com.ntech.cabosse.governance.dto.ApprovalDtos.PendingApprovalDto;
import com.ntech.cabosse.membercredit.entity.MemberCreditStatus;
import com.ntech.cabosse.membercredit.repository.MemberCreditRepository;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qui attend une décision, rassemblé pour celui qui la prend.
 *
 * <p>Aucun état nouveau : chaque source porte déjà l'information qui dit
 * qu'elle attend. Le travail est de la ramener au bon endroit, avec ce
 * qu'il faut pour décider plutôt que le seul montant.</p>
 *
 * <p>Le solde du compte courant en fait partie, et ce n'est pas de la
 * décoration : refinancer un délégué qui traîne un encours est une
 * décision, et elle ne se prend pas sans le voir. Un constat, jamais une
 * garde : rien ici ne bloque, la gouvernance tranche.</p>
 */
@ApplicationScoped
public class ApprovalQueueService {

    @Inject CollectorAdvanceRepository advances;
    @Inject MemberCreditRepository credits;
    @Inject DelegateAccountService delegateAccount;
    @Inject PermissionResolver permissions;

    /**
     * @param kind   restreint à une nature, {@code null} pour les deux
     * @param siteId restreint à un site, {@code null} pour tous
     */
    public ApprovalQueueDto queue(String kind, UUID siteId, PageRequest pr) {
        List<PendingApprovalDto> all = collect(kind, siteId);

        // Le plus ancien d'abord, comme les files de trésorerie : ce qui
        // attend depuis le plus longtemps coûte le plus cher en confiance.
        // À date égale la référence départage, sans quoi deux
        // rechargements rendraient deux ordres et une ligne sauterait
        // d'une page à l'autre.
        all.sort(Comparator.comparing(PendingApprovalDto::since)
                .thenComparing(p -> p.sourceRef() == null ? "" : p.sourceRef()));

        BigDecimal total = all.stream()
                .map(PendingApprovalDto::amountFcfa)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int from = Math.min(pr.skip(), all.size());
        int to = Math.min(from + pr.perPage(), all.size());
        List<PendingApprovalDto> items = new ArrayList<>(all.subList(from, to));

        Map<String, String> filters = new HashMap<>();
        if (kind != null && !kind.isBlank()) filters.put("kind", kind);
        if (siteId != null) filters.put("siteId", siteId.toString());

        long oldest = all.isEmpty() ? 0 : all.get(0).ageDays();

        Pagination<PendingApprovalDto> page = Pagination.of(
                all.size(), pr, new String[]{"since"}, "asc", filters, items);
        return new ApprovalQueueDto(total, all.size(), oldest, page);
    }

    private List<PendingApprovalDto> collect(String kind, UUID siteId) {
        List<PendingApprovalDto> out = new ArrayList<>();
        if (wants(kind, ApprovalKind.COLLECTOR_ADVANCE)) collectAdvances(out);
        if (wants(kind, ApprovalKind.MEMBER_CREDIT)) collectCredits(out);
        if (siteId == null) return out;
        // Une demande sans site connu reste visible : la masquer sur un
        // filtre de site la ferait disparaître du total soumis au conseil.
        return out.stream()
                .filter(p -> p.siteId() == null || siteId.equals(p.siteId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean wants(String kind, ApprovalKind candidate) {
        return kind == null || kind.isBlank() || kind.equals(candidate.name());
    }

    /** Les avances aux délégués : c'est ici qu'elles se décident. */
    private void collectAdvances(List<PendingApprovalDto> out) {
        boolean canApprove = permissions.currentIsTenantAdmin()
                || permissions.can(Permission.COLLECTION_ADVANCE_APPROVE);
        boolean canApproveGovernance = permissions.currentIsTenantAdmin()
                || permissions.can(Permission.COLLECTION_ADVANCE_APPROVE_GOVERNANCE);

        advances.findByStatus(CollectorAdvanceStatus.PENDING_APPROVAL.name()).forEach(a -> {
            // Ce que le délégué traîne déjà, pour voir si on a affaire à
            // quelqu'un qui nous doit. Le calcul touche plusieurs
            // collections : il ne vaut que sur ce qui attend vraiment, et
            // ce qui attend se compte en dizaines.
            BigDecimal balance = a.delegateSupplierId != null
                    ? delegateAccount.outstanding(a.delegateSupplierId, a.campaignId)
                    : null;
            out.add(new PendingApprovalDto(
                    ApprovalKind.COLLECTOR_ADVANCE.name(), a.id, a.ref,
                    a.delegateSupplierId, a.delegateName,
                    a.advanceAmountFcfa, a.advanceDate, ageOf(a.advanceDate),
                    balance, a.expectedQuantity, a.expectedQuantityUnit,
                    a.notes, a.createdByEmail,
                    a.governanceApprovalRequired,
                    canApprove && (!a.governanceApprovalRequired || canApproveGovernance),
                    a.siteId, a.campaignId));
        });
    }

    /**
     * Les crédits aux producteurs, en consultation.
     *
     * <p>La direction tranche seule sur ces dossiers, vu les montants.
     * Offrir un bouton d'approbation ici contredirait ce circuit : la file
     * les montre pour que le conseil sache ce qui se passe, pas pour qu'il
     * s'y substitue.</p>
     */
    private void collectCredits(List<PendingApprovalDto> out) {
        credits.findByStatus(MemberCreditStatus.PENDING_APPROVAL.name()).forEach(c -> out.add(
                new PendingApprovalDto(
                        ApprovalKind.MEMBER_CREDIT.name(), c.id, c.ref,
                        c.memberId, c.memberName,
                        c.amountFcfa, c.requestedAt, ageOf(c.requestedAt),
                        null, null, null,
                        c.notes, c.requestedByEmail,
                        c.governanceApprovalRequired,
                        false,
                        null, c.campaignId)));
    }

    /** Ancienneté en jours, sur l'horloge du serveur. */
    private static long ageOf(LocalDate since) {
        if (since == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(since, LocalDate.now()));
    }
}
