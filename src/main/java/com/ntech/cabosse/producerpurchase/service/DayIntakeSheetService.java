package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.producerpurchase.dto.DayIntakeRowDto;
import com.ntech.cabosse.producerpurchase.dto.DayIntakeRowKind;
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
import java.time.Instant;
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
    @Inject com.ntech.cabosse.commodity.repository.CommoditySaleRepository commoditySales;

    public DayIntakeSheetDto build(LocalDate date, UUID siteId, UUID articleId) {
        if (date == null) {
            throw new BusinessException(Messages.msg("m.pds-date-required"));
        }
        List<ProducerPurchaseEntity> receipts = purchases.listByDateAndSite(date, siteId, articleId);

        // Sans article demandé, la fiche en adopte un seul si la journée
        // n'en a reçu qu'un : c'est le cas courant du magasin, et c'est ce
        // qui permet de rendre l'ouverture sans la fausser.
        // L'amorçage du jour fait partie de la journée : le jour où le
        // magasin pose son stock de départ, la fiche n'avait aucun reçu,
        // ne savait pas de quel article parler, et s'affichait vide
        // (signalé le 12/10/2026).
        java.time.Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        java.time.Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<com.ntech.cabosse.stock.entity.StockMovementEntity> openings =
                movements.listOpeningsOn(dayStart, dayEnd, siteId, articleId);

        UUID sheetArticleId = articleId;
        String articleName = null;
        if (sheetArticleId == null) {
            List<UUID> distinct = java.util.stream.Stream.concat(
                            receipts.stream().map(r -> r.articleId),
                            openings.stream().map(m -> m.articleId))
                    .distinct().toList();
            if (distinct.size() == 1) sheetArticleId = distinct.get(0);
        }
        if (sheetArticleId != null) {
            UUID finalId = sheetArticleId;
            articleName = receipts.stream()
                    .filter(r -> finalId.equals(r.articleId))
                    .map(r -> r.articleName).filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElseGet(() -> openings.stream()
                            .filter(m -> finalId.equals(m.articleId))
                            .map(m -> m.articleName).filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null));
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
                        - dispatchNotes.sumBagsBefore(date, siteId, sheetArticleId)
                        // Une vente en gros sans bordereau sort pourtant
                        // des sacs : sans elle le compte restait trop
                        // haut, définitivement (12/10/2026).
                        - commoditySales.sumBagsWithoutDispatch(
                                null, date, siteId, sheetArticleId);
            }
            // Juste avant minuit, pas à minuit : les mouvements d'un reçu
            // sont horodatés au début exact de leur journée, et une photo
            // prise au même instant les compterait dans le report. La
            // fiche additionnerait alors deux fois les entrées du jour.
            opening = stockService.snapshotAt(sheetArticleId, siteId,
                    date.atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)).quantity();
        }

        List<DayIntakeRowDto> rows = new ArrayList<>(receipts.size() + openings.size());
        BigDecimal runningQty = opening != null ? opening : BigDecimal.ZERO;
        int runningBags = openingBags != null ? openingBags : 0;
        int enteredBags = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        // L'amorçage vient en tête : c'est le point de départ de la
        // journée. Il compte dans les totaux comme les entrées, le
        // carnet du magasin totalisant ce qui est entré en magasin et
        // non ce qui a été acheté (expert, 13/10/2026).
        for (com.ntech.cabosse.stock.entity.StockMovementEntity m : openings) {
            if (sheetArticleId != null && !sheetArticleId.equals(m.articleId)) continue;
            BigDecimal weight = nz(m.quantitySigned).abs();
            int bags = m.bags != null ? m.bags : 0;
            runningQty = runningQty.add(weight);
            runningBags += bags;
            enteredBags += bags;
            totalWeight = totalWeight.add(weight);
            totalAmount = totalAmount.add(nz(m.total));
            // Pas de nom d'apporteur : un amorçage n'en a pas, et
            // l'écran le nomme lui-même dans la langue de la personne.
            rows.add(new DayIntakeRowDto(
                    null, date, null, m.ref, null, null,
                    m.bags, weight, m.unitPrice, nz(m.total),
                    runningQty, runningBags,
                    "OPENING", null));
        }
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

        // Ce qui est sorti du magasin dans la journée. Sans ligne sur la
        // fiche, les sorties ayant leur propre pièce, mais retranché de
        // la clôture : sinon le report du lendemain ne tombe pas sur
        // elle (12/10/2026).
        BigDecimal outflowWeight = sheetArticleId == null || siteId == null
                ? BigDecimal.ZERO
                : movements.sumOutflowsOn(dayStart, dayEnd, siteId, sheetArticleId);
        int outflowBags = sheetArticleId == null || siteId == null ? 0
                : dispatchNotes.sumBagsBetween(date, date.plusDays(1), siteId, sheetArticleId)
                        + commoditySales.sumBagsWithoutDispatch(
                                date, date.plusDays(1), siteId, sheetArticleId);
        runningQty = runningQty.subtract(outflowWeight);
        runningBags -= outflowBags;

        return new DayIntakeSheetDto(date, siteId, sheetArticleId, articleName,
                opening, openingBags, rows, totalWeight, totalAmount, enteredBags, runningQty,
                correctedWeight, correctedBags, runningBags,
                outflowWeight, outflowBags);
    }

    /**
     * Le registre détaillé du magasin : le même carnet, mais courant sur
     * une plage de jours au lieu d'une journée.
     *
     * <p>Le magasinier tient un registre qui part du stock de départ et
     * court jusqu'au jour lu, cumuls compris (expert, 14/10/2026). La
     * fiche du jour ne sait montrer qu'une journée, et le cumul y repart
     * du report à chaque ouverture.</p>
     *
     * <p>Les sorties y ont leur ligne, contrairement à la fiche du jour :
     * un registre dont le cumul ne se vérifie pas ne sert à rien.</p>
     */
    public DayIntakeSheetDto buildRange(LocalDate from, LocalDate to, UUID siteId, UUID articleId) {
        if (from == null || to == null) {
            throw new BusinessException(Messages.msg("m.pds-date-required"));
        }
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        Instant rangeStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<ProducerPurchaseEntity> receipts =
                purchases.listBetweenAndSite(from, to, siteId, articleId);
        List<com.ntech.cabosse.stock.entity.StockMovementEntity> openings =
                movements.listOpeningsOn(rangeStart, rangeEnd, siteId, articleId);
        List<StockCorrectionEntity> corrections =
                correctionRepo.listBetweenAndSite(from, to, siteId, articleId);
        List<com.ntech.cabosse.stock.entity.StockMovementEntity> outflows =
                movements.listOutflowsBetween(rangeStart, rangeEnd, siteId, articleId);

        UUID sheetArticleId = articleId;
        String articleName = null;
        if (sheetArticleId == null) {
            List<UUID> distinct = java.util.stream.Stream.concat(
                            receipts.stream().map(r -> r.articleId),
                            openings.stream().map(m -> m.articleId))
                    .distinct().toList();
            if (distinct.size() == 1) sheetArticleId = distinct.get(0);
        }
        if (sheetArticleId != null) {
            UUID finalId = sheetArticleId;
            articleName = receipts.stream().filter(r -> finalId.equals(r.articleId))
                    .map(r -> r.articleName).filter(java.util.Objects::nonNull).findFirst()
                    .orElseGet(() -> openings.stream().filter(m -> finalId.equals(m.articleId))
                            .map(m -> m.articleName).filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null));
        }

        BigDecimal opening = null;
        Integer openingBags = null;
        if (sheetArticleId != null && siteId != null) {
            Integer anchor = movements.openingBags(sheetArticleId, siteId, rangeStart);
            if (anchor != null) {
                openingBags = anchor
                        + purchases.sumBagsBefore(from, siteId, sheetArticleId)
                        - correctionRepo.sumBagsBefore(from, siteId, sheetArticleId)
                        - dispatchNotes.sumBagsBefore(from, siteId, sheetArticleId)
                        - commoditySales.sumBagsWithoutDispatch(null, from, siteId, sheetArticleId);
            }
            opening = stockService.snapshotAt(sheetArticleId, siteId,
                    rangeStart.minusMillis(1)).quantity();
        }

        // Tout ce que la période a bougé, dans l'ordre du carnet : le
        // stock de départ ouvre le jour, les entrées suivent, les
        // corrections viennent après ce qui est entré, les sorties
        // ferment.
        record Event(LocalDate date, int order, DayIntakeRowKind kind,
                     Object payload) {}
        List<Event> events = new ArrayList<>();
        for (var m : openings) {
            if (sheetArticleId != null && !sheetArticleId.equals(m.articleId)) continue;
            events.add(new Event(dayOf(m.occurredAt), 0, DayIntakeRowKind.OPENING, m));
        }
        for (ProducerPurchaseEntity r : receipts) {
            events.add(new Event(r.date, 1, DayIntakeRowKind.RECEIPT, r));
        }
        for (StockCorrectionEntity c : corrections) {
            events.add(new Event(c.date, 2, DayIntakeRowKind.CORRECTION, c));
        }
        for (var m : outflows) {
            if (sheetArticleId != null && !sheetArticleId.equals(m.articleId)) continue;
            events.add(new Event(dayOf(m.occurredAt), 3, DayIntakeRowKind.OUTFLOW, m));
        }
        events.sort(java.util.Comparator
                .comparing(Event::date, java.util.Comparator.nullsLast(LocalDate::compareTo))
                .thenComparingInt(Event::order));

        List<DayIntakeRowDto> rows = new ArrayList<>(events.size());
        BigDecimal runningQty = opening != null ? opening : BigDecimal.ZERO;
        int runningBags = openingBags != null ? openingBags : 0;
        int enteredBags = 0;
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal correctedWeight = BigDecimal.ZERO;
        int correctedBags = 0;
        BigDecimal outflowWeight = BigDecimal.ZERO;
        int outflowBags = 0;

        for (Event event : events) {
            switch (event.kind()) {
                case OPENING -> {
                    var m = (com.ntech.cabosse.stock.entity.StockMovementEntity) event.payload();
                    BigDecimal weight = nz(m.quantitySigned).abs();
                    int bags = m.bags != null ? m.bags : 0;
                    runningQty = runningQty.add(weight);
                    runningBags += bags;
                    enteredBags += bags;
                    totalWeight = totalWeight.add(weight);
                    totalAmount = totalAmount.add(nz(m.total));
                    rows.add(new DayIntakeRowDto(null, event.date(), null, m.ref, null, null,
                            m.bags, weight, m.unitPrice, nz(m.total),
                            runningQty, runningBags, "OPENING", null));
                }
                case RECEIPT -> {
                    ProducerPurchaseEntity r = (ProducerPurchaseEntity) event.payload();
                    BigDecimal weight = nz(r.weightKg);
                    runningQty = runningQty.add(weight);
                    runningBags += r.nbSacs != null ? r.nbSacs : 0;
                    enteredBags += r.nbSacs != null ? r.nbSacs : 0;
                    totalWeight = totalWeight.add(weight);
                    totalAmount = totalAmount.add(nz(r.amount));
                    rows.add(new DayIntakeRowDto(r.id, r.date,
                            r.delegateName != null ? r.delegateName : r.producerName,
                            r.ref, r.deliveryRef,
                            r.delegateName != null ? "DELEGATE" : "PRODUCER",
                            r.nbSacs, weight, r.guaranteedPricePerKg, nz(r.amount),
                            runningQty, runningBags));
                }
                case CORRECTION -> {
                    StockCorrectionEntity c = (StockCorrectionEntity) event.payload();
                    BigDecimal removed = nz(c.weightKg);
                    int bags = c.bags != null ? c.bags : 0;
                    runningQty = runningQty.subtract(removed);
                    runningBags -= bags;
                    correctedWeight = correctedWeight.add(removed);
                    correctedBags += bags;
                    rows.add(new DayIntakeRowDto(null, c.date, c.reason, c.ref, null, null,
                            bags == 0 ? null : -bags, removed.negate(), null, null,
                            runningQty, runningBags, "CORRECTION", c.id));
                }
                case OUTFLOW -> {
                    var m = (com.ntech.cabosse.stock.entity.StockMovementEntity) event.payload();
                    BigDecimal left = nz(m.quantitySigned).abs();
                    int bags = m.bags != null ? m.bags : 0;
                    runningQty = runningQty.subtract(left);
                    runningBags -= bags;
                    outflowWeight = outflowWeight.add(left);
                    outflowBags += bags;
                    rows.add(new DayIntakeRowDto(null, event.date(),
                            m.sourceRef, m.ref, null, null,
                            bags == 0 ? null : -bags, left.negate(), null, null,
                            runningQty, runningBags, "OUTFLOW", null));
                }
            }
        }

        return new DayIntakeSheetDto(to, siteId, sheetArticleId, articleName,
                opening, openingBags, rows, totalWeight, totalAmount, enteredBags, runningQty,
                correctedWeight, correctedBags, runningBags,
                outflowWeight, outflowBags);
    }

    private static LocalDate dayOf(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
