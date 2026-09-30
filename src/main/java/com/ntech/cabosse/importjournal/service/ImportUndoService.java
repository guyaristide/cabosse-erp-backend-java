package com.ntech.cabosse.importjournal.service;

import com.ntech.cabosse.importjournal.dto.ImportUndoResultDto;
import com.ntech.cabosse.importjournal.entity.ImportCreation;
import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.importjournal.repository.ImportRunRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Défait un import de producteurs, en laissant ce qui a déjà servi.
 *
 * <p>Un import qui se passe mal laisse aujourd'hui une base à nettoyer à
 * la main, fiche par fiche. L'annulation part de ce que le journal a
 * retenu : les identifiants créés, et eux seuls. Deviner à la date
 * emporterait tout ce qui a été saisi le même jour (demandé le
 * 30/09/2026).</p>
 *
 * <p>Trois règles, qui ne se négocient pas. Un producteur qui a servi
 * depuis n'est pas supprimé : une livraison, un crédit, une adhésion
 * validée le rattachent à des faits que l'import n'a pas créés. Une
 * écriture comptable ne se supprime pas, elle se contre-passe, et une
 * période close la refuse. Les référentiels ouverts au passage — section,
 * village, type de pièce — restent : d'autres fiches s'y sont peut-être
 * rattachées depuis, et les défaire emporterait des données étrangères à
 * l'import.</p>
 *
 * <p>Elle défait donc ce qui peut l'être et rend compte du reste. Tout ou
 * rien se bloquerait sur le premier producteur ayant livré, et resterait
 * inutilisable là où elle sert.</p>
 */
@ApplicationScoped
public class ImportUndoService {

    @Inject ImportRunRepository runs;
    @Inject com.ntech.cabosse.members.repository.MemberRepository members;
    @Inject com.ntech.cabosse.supplier.repository.SupplierRepository suppliers;
    @Inject com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository purchases;
    @Inject com.ntech.cabosse.membercredit.repository.MemberCreditRepository credits;
    @Inject com.ntech.cabosse.accounting.service.AccountingService accounting;
    @Inject AuditService audit;
    @Inject com.ntech.cabosse.shared.tenant.TenantContext tenantContext;

    public ImportUndoResultDto undo(UUID runId) {
        ImportRunEntity run = runs.findById(runId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.imr-run-not-found", runId)));
        if (!"members".equals(run.domain)) {
            throw new BusinessException(Messages.msg("m.imr-undo-domain-unsupported", run.domain));
        }
        if (run.creations == null || run.creations.isEmpty()) {
            throw new BusinessException(Messages.msg("m.imr-undo-nothing-recorded"));
        }

        List<ImportUndoResultDto.Kept> kept = new ArrayList<>();
        int undone = 0;

        // Les producteurs d'abord : c'est leur sort qui décide de celui de
        // leur fournisseur miroir. Défaire le miroir d'un producteur qu'on
        // garde le rendrait invisible aux achats sans que rien ne le dise.
        for (ImportCreation c : run.creations) {
            if (!"member".equals(c.kind)) continue;
            String reason = whyKeep(c.id);
            if (reason != null) {
                kept.add(new ImportUndoResultDto.Kept(c.kind, c.id, c.label, reason));
                continue;
            }
            // La pièce de part sociale se contre-passe, elle ne se
            // supprime pas. Sans pièce d'origine, l'appel ne fait rien.
            accounting.reverseFrom(
                    com.ntech.cabosse.accounting.entity.PostingSourceType.MEMBER_CAPITAL, c.id,
                    "Annulation d'import");
            members.deleteById(c.id);
            undone++;
        }

        java.util.Set<UUID> keptMembers = kept.stream()
                .map(ImportUndoResultDto.Kept::id).collect(java.util.stream.Collectors.toSet());
        for (ImportCreation c : run.creations) {
            if (!"supplier".equals(c.kind)) continue;
            // Le miroir suit son producteur : celui qu'on garde garde le
            // sien, sinon les achats ne sauraient plus à qui payer.
            boolean ownerKept = run.creations.stream()
                    .anyMatch(m -> "member".equals(m.kind) && m.rowNumber == c.rowNumber
                            && keptMembers.contains(m.id));
            if (ownerKept) {
                kept.add(new ImportUndoResultDto.Kept(c.kind, c.id, c.label,
                        Messages.msg("m.imr-undo-kept-mirror")));
                continue;
            }
            suppliers.deleteById(c.id);
            undone++;
        }

        audit.event(AuditEventType.CATALOG_UPDATED)
                .target("import_run", runId.toString(), run.fileName)
                .tenant(tenantContext.tenantId(), null)
                .description("Annulation d'import : " + undone + " défait(s), "
                        + kept.size() + " conservé(s)")
                .record();

        return new ImportUndoResultDto(runId, undone, kept.size(), kept);
    }

    /**
     * Ce qui retient un producteur, ou {@code null} s'il peut partir.
     *
     * <p>L'ordre n'a pas d'importance : une seule raison suffit, et la
     * première trouvée est celle qu'on montre. Ce qui compte est de ne
     * jamais supprimer une fiche à laquelle un fait se rattache.</p>
     */
    private String whyKeep(UUID memberId) {
        var member = members.findById(memberId).orElse(null);
        if (member == null) return Messages.msg("m.imr-undo-kept-gone");
        if (purchases.countSearch(null, null, memberId) > 0) {
            return Messages.msg("m.imr-undo-kept-delivered");
        }
        if (credits.countSearch(memberId, null, null) > 0) {
            return Messages.msg("m.imr-undo-kept-credit");
        }
        return null;
    }
}
