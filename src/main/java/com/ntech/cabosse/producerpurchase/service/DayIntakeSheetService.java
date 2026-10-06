package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto;
import com.ntech.cabosse.producerpurchase.dto.DayIntakeSheetDto;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.dispatch.repository.DispatchNoteRepository;
import com.ntech.cabosse.stock.entity.StockCorrectionEntity;
import com.ntech.cabosse.stock.repository.StockCorrectionRepository;
import com.ntech.cabosse.stock.repository.StockMovementRepository;
import com.ntech.cabosse.stock.service.StockCorrectionService;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fiche de stock du jour (épic magasin, CE-185).
 *
 * <p>La vue journal du magasinier : la photo du stock à l'ouverture, puis
 * chaque bordereau dans l'ordre de saisie avec ses cumuls, en quantité et
 * en sacs, comme il la tient sur le carnet. Elle ne recalcule rien : elle
 * relit les reçus du jour et la photo à date que le stock sait déjà
 * produire.</p>
 *
 * <p>Les corrections de magasin viennent après les entrées, comme sur le
 * carnet : le brassage se fait sur ce qui est reçu. Elles retirent des
 * sacs et du poids, et la clôture en tient compte. Les bordereaux de
 * sortie restent hors de la fiche, ils ont leur propre pièce.</p>
 */
@ApplicationScoped
public class DayIntakeSheetService {

    @Inject ProducerPurchaseRepository purchases;
    @Inject StockService stockService;
    @Inject StockCorrectionService corrections;
    @Inject StockMovementRepository movements;
    @Inject StockCorrectionRepository correctionRepo;
    @Inject DispatchNoteRepository dispatchNotes;

    public DayIntakeSheetDto build(LocalDate date, UUID siteId, UUID articleId) {
        if (date == null) {
            throw new BusinessException(Messages.msg("m.pds-date-required"));
        }
        List<ProducerPurchaseEntity> receipts = purchases.listByDateAndSite(date, siteId, articleId);

        // Sans article demandé, la fiche en adopte un seul si la journée
        // n'en a reçu qu'un : c'est le cas courant du magasin, et c'est ce
        // qui permet de rendre l'ouverture sans la fausser.
        UUID sheetArticleId = articleId;
        String articleName = null;
        if (sheetArticleId == null) {
            List<UUID> distinct = receipts.stream().map(r -> r.articleId).distinct().toList();
            if (distinct.size() == 1) sheetArticleId = distinct.get(0);
        }
        if (sheetArticleId != null) {
            UUID finalId = sheetArticleId;
            articleName = receipts.stream()
                    .filter(r -> finalId.equals(r.articleId))
                    .map(r -> r.articleName).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        }

        BigDecimal opening = null;
        Integer openingBags = null;
        if (sheetArticleId != null && siteId != null) {
            // Les sacs n'ont pas de photo : le stock ne les suit pas. Leur
            // compte se reconstitue depuis celui posé à l'amorçage, plus
            // ce qui est entré, moins ce qui est sorti. Sans amorçage en
            // sacs, on ne prétend pas savoir : la fiche s'en tient alors
            // au solde de la journée.
            Integer anchor = movements.openingBags(sheetArticleId, siteId,
                    date.atStartOfDay(ZoneOffset.UTC).toInstant());
            if (anchor != null) {
                openingBags = anchor
                        + purchases.sumBagsBefore(date, siteId, sheetArticleId)
                        - correctionRepo.sumBagsBefore(date, siteId, sheetArticleId)
                        - dispatchNotes.sumBagsBefore(date, siteId, sheetArticleId);
            }
            // Juste avant minuit, pas à minuit : les mouvements d'un reçu
            // sont horodatés au début exact de leur journée, et une photo
            // prise au même instant les compterait dans le report. La
            // fiche additionnerait alors deux fois les entrées du jour.
            opening = stockService.snapshotAt(sheetArticleId, siteId,
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)).quantity();
        }

        List<DayIntakeRowDto> rows = new ArrayList<>(receipts.size());
        BigDecimal runningQty = opening != null ? opening : BigDecimal.ZERO;
        int runningBags = openingBags != null ? openingBags : 0;
        int enteredBags = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ProducerPurchaseEntity r : receipts) {
            BigDecimal weight = nz(r.weightKg);
            runningQty = runningQty.add(weight);
            runningBags += r.nbSacs != null ? r.nbSacs : 0;
            enteredBags += r.nbSacs != null ? r.nbSacs : 0;
            totalWeight = totalWeight.add(weight);
            totalAmount = totalAmount.add(nz(r.amount));
            rows.add(new DayIntakeRowDto(
                    r.id, r.date,
                    r.delegateName != null ? r.delegateName : r.producerName,
                    r.ref, r.deliveryRef,
                    r.delegateName != null ? "DELEGATE" : "PRODUCER",
                    r.nbSacs, weight, r.guaranteedPricePerKg, nz(r.amount),
                    runningQty, runningBags));
        }

        // Le brassage porte sur ce qui vient d'entrer : les corrections
        // suivent les entrées sur le carnet, et le serveur les date à midi
        // pour que le stock les rejoue dans le même ordre.
        BigDecimal correctedWeight = BigDecimal.ZERO;
        int correctedBags = 0;
        for (StockCorrectionEntity c : corrections.listForDay(date, siteId, sheetArticleId)) {
            BigDecimal removed = nz(c.weightKg);
            int bags = c.bags != null ? c.bags : 0;
            runningQty = runningQty.subtract(removed);
            runningBags -= bags;
            correctedWeight = correctedWeight.add(removed);
            correctedBags += bags;
            rows.add(new DayIntakeRowDto(
                    null, c.date, c.reason, c.ref, null, null,
                    bags == 0 ? null : -bags, removed.negate(), null, null,
                    runningQty, runningBags,
                    "CORRECTION", c.id));
        }

        return new DayIntakeSheetDto(date, siteId, sheetArticleId, articleName,
                opening, openingBags, rows, totalWeight, totalAmount, enteredBags, runningQty,
                correctedWeight, correctedBags, runningBags);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
