package com.ntech.cabosse.commodity.service;

import com.ntech.cabosse.commodity.dto.CommoditySaleImportCommitResponseDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportPreviewDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportPreviewDto.FieldIssue;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportPreviewDto.Normalized;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportPreviewDto.Row;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportPreviewDto.Status;
import com.ntech.cabosse.commodity.dto.CommoditySaleImportRowDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.ImportFields;
import com.ntech.cabosse.shared.imports.NameAliases;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Import de masse des ventes cacao (backlog NEG-02). Rapproche le client par
 * nom et le produit (code ou libellé) ; le prix dérive de {@code montantFacture ÷
 * poids accepté}. Le commit délègue à {@link CommoditySaleService#create}.
 */
@ApplicationScoped
public class CommoditySaleImportService {

    @Inject CustomerRepository customers;
    @Inject com.ntech.cabosse.dispatch.repository.DispatchNoteRepository dispatchNotes;
    @Inject ArticleRepository articles;
    @Inject CommoditySaleService saleService;

    @jakarta.inject.Inject
    com.ntech.cabosse.qualitygrade.service.QualityGradeService qualityGrades;

    public CommoditySaleImportPreviewDto preview(List<CommoditySaleImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new CommoditySaleImportPreviewDto(0, 0, 0, List.of());
        }
        // Le client, sous toutes les écritures de son nom : le fichier
        // de l'usine écrit « IVORY COCOA PRODUCTS (ICP) » là où la fiche
        // porte le sigle seul (16/10/2026).
        Map<String, List<CustomerEntity>> customersByKey = new HashMap<>();
        for (CustomerEntity c : customers.listAll()) {
            for (String key : NameAliases.of(c.name)) {
                customersByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }
        // Articles vendables, rapprochés par code OU nom. Ceux qui ne le
        // sont pas sont indexés à part : « inconnu » envoyait créer un
        // article qui existait déjà, alors qu'il manquait une case.
        Map<String, ArticleEntity> articlesByKey = new HashMap<>();
        Map<String, ArticleEntity> notSellableByKey = new HashMap<>();
        for (ArticleEntity a : articles.listAll()) {
            Map<String, ArticleEntity> target = a.sellable && a.active
                    ? articlesByKey : notSellableByKey;
            for (String key : NameAliases.of(a.code)) target.putIfAbsent(key, a);
            for (String key : NameAliases.of(a.name)) target.putIfAbsent(key, a);
        }

        Grouping grouping = group(input);

        List<Row> rows = new ArrayList<>();
        int ready = 0, invalid = 0;
        for (CommoditySaleImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            // Un chargement de plus sur la vente d'une autre ligne : il ne
            // se juge que sur son bordereau, le reste appartient à la vente.
            LoadRow attached = grouping.loads.get(raw.rowNumber());
            if (attached != null) {
                Normalized loadOnly = new Normalized(null, null, null, null, null,
                        parseDecimal(raw.declaredKg()), null, null);
                if (attached.error() != null) {
                    invalid++;
                    rows.add(new Row(raw.rowNumber(), Status.INVALID, loadOnly,
                            List.of(new FieldIssue("dispatchNoteNumber", attached.error()))));
                } else {
                    rows.add(new Row(raw.rowNumber(), Status.ATTACHED, loadOnly, List.of(),
                            List.of(new FieldIssue("dispatchNoteNumber", Messages.msg(
                                    "m.imp-load-attached", attached.headRowNumber(), attached.note().ref)))));
                }
                continue;
            }
            if (grouping.orphans.contains(raw.rowNumber())) {
                invalid++;
                rows.add(new Row(raw.rowNumber(), Status.INVALID,
                        new Normalized(null, null, null, null, null, parseDecimal(raw.declaredKg()), null, null),
                        List.of(new FieldIssue("dispatchNoteNumber", Messages.msg("m.imp-load-row-orphan")))));
                continue;
            }

            CustomerEntity customer = null;
            boolean ambiguousCustomer = false;
            for (String key : NameAliases.of(raw.customerName())) {
                List<CustomerEntity> hits = customersByKey.getOrDefault(key, List.of());
                if (hits.size() == 1) { customer = hits.get(0); break; }
                if (hits.size() > 1) { ambiguousCustomer = true; break; }
            }
            if (customer == null) {
                issues.add(new FieldIssue("customerName", ambiguousCustomer
                        ? Messages.msg("m.imp-customer-ambiguous", blankToNull(raw.customerName()))
                        : Messages.msg("m.imp-customer-not-found")));
            }

            ArticleEntity article = null;
            ArticleEntity notSellable = null;
            for (String key : NameAliases.of(raw.productCode())) {
                article = articlesByKey.get(key);
                if (article != null) break;
                if (notSellable == null) notSellable = notSellableByKey.get(key);
            }
            if (article == null) {
                // L'article existe, il n'est simplement pas ouvert à la
                // vente : le geste est une case à cocher sur sa fiche,
                // pas la création d'un doublon.
                issues.add(new FieldIssue("productCode", notSellable != null
                        ? Messages.msg("m.imp-article-not-sellable", notSellable.name)
                        : Messages.msg("m.imp-sellable-article-unknown")));
            }

            LocalDate date = parseDate(raw.date());
            if (date == null) issues.add(new FieldIssue("date", Messages.msg("m.imp-date-invalid-or-missing")));
            if (parseUuid(raw.siteId()) == null) issues.add(new FieldIssue("siteId", Messages.msg("m.imp-departure-site-required")));

            BigDecimal declared = parseDecimal(raw.declaredKg());
            BigDecimal accepted = parseDecimal(raw.acceptedKg());
            if (declared == null || declared.signum() <= 0)
                issues.add(new FieldIssue("declaredKg", Messages.msg("m.imp-declared-weight-required")));
            if (accepted == null || accepted.signum() <= 0)
                issues.add(new FieldIssue("acceptedKg", Messages.msg("m.imp-accepted-weight-required")));

            BigDecimal montant = parseDecimal(raw.montantFacture());

            // Le grade était refusé à l'enregistrement sans que l'aperçu
            // s'en aperçoive : « prête », puis écartée. Il se juge donc
            // ici, sur les mêmes règles, et un grade simplement absent
            // s'ouvre au lieu de faire échouer le fichier.
            List<FieldIssue> notices = new ArrayList<>();
            String grade = blankToNull(raw.grade());
            if (grade != null) {
                switch (qualityGrades.planForImport(grade)) {
                    case WILL_BE_CREATED -> notices.add(new FieldIssue("grade",
                            Messages.msg("m.imp-grade-will-be-created", grade)));
                    case INACTIVE -> issues.add(new FieldIssue("grade",
                            Messages.msg("m.qgr-inactive", grade)));
                    case EXISTS -> { }
                }
            }

            // Le chargement est-il déjà sorti sur un bordereau ? La vente
            // l'appelle alors au lieu de redemander du stock. Le dire ici
            // évite le « stock insuffisant » sur une marchandise partie.
            String noteRef = blankToNull(raw.dispatchNoteNumber());
            boolean headNoteOpen = false;
            if (noteRef != null) {
                var note = dispatchNotes.findByRef(noteRef);
                if (note.isPresent() && note.get().status
                        == com.ntech.cabosse.dispatch.entity.DispatchNoteStatus.OPEN) {
                    headNoteOpen = true;
                    notices.add(new FieldIssue("dispatchNoteNumber",
                            Messages.msg("m.imp-dispatch-note-called", note.get().ref)));
                } else {
                    notices.add(new FieldIssue("dispatchNoteNumber",
                            Messages.msg("m.imp-dispatch-note-unknown", noteRef)));
                }
            }

            // Les chargements que d'autres lignes joignent à cette vente :
            // le poids déclaré les additionne, et la vente ne se fait pas
            // sans eux, ni sans un bordereau ouvert pour elle-même.
            List<LoadRow> loads = grouping.loadsByHead.getOrDefault(raw.rowNumber(), List.of());
            if (!loads.isEmpty()) {
                String loadRows = loads.stream().map(l -> String.valueOf(l.raw().rowNumber()))
                        .collect(java.util.stream.Collectors.joining(", "));
                if (!headNoteOpen) {
                    issues.add(new FieldIssue("dispatchNoteNumber",
                            Messages.msg("m.imp-head-note-required", loadRows)));
                }
                for (LoadRow l : loads) {
                    if (l.error() != null) {
                        issues.add(new FieldIssue("dispatchNoteNumber",
                                Messages.msg("m.imp-load-row-invalid", l.raw().rowNumber())));
                    } else {
                        notices.add(new FieldIssue("dispatchNoteNumber",
                                Messages.msg("m.imp-dispatch-note-called", l.note().ref)));
                        BigDecimal more = parseDecimal(l.raw().declaredKg());
                        if (declared != null && more != null) declared = declared.add(more);
                    }
                }
            }

            Status status = issues.isEmpty() ? Status.READY : Status.INVALID;
            if (status == Status.READY) ready++; else invalid++;

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(
                            customer != null ? customer.id : null,
                            customer != null ? customer.name : null,
                            article != null ? article.id : null,
                            article != null ? article.name : null,
                            date != null ? date.toString() : null,
                            declared, accepted, montant),
                    issues, notices));
        }
        return new CommoditySaleImportPreviewDto(input.size(), ready, invalid, rows);
    }

    public CommoditySaleImportCommitResponseDto commit(List<CommoditySaleImportRowDto> input) {
        CommoditySaleImportPreviewDto preview = preview(input);
        Map<Integer, CommoditySaleImportRowDto> byRow = new HashMap<>();
        if (input != null) input.forEach(r -> byRow.put(r.rowNumber(), r));

        List<String> createdRefs = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        Grouping grouping = group(input);
        for (Row row : preview.rows()) {
            // Un chargement joint part avec sa vente : ni créé, ni écarté.
            if (row.status() == Status.ATTACHED) continue;
            if (row.status() != Status.READY || row.normalized() == null) { skipped.add(row); continue; }
            CommoditySaleImportRowDto raw = byRow.get(row.rowNumber());
            Normalized nrm = row.normalized();
            // Le bordereau de la ligne, puis ceux des chargements joints,
            // chacun avec ce que le carnet en dit.
            UUID headNoteId = dispatchNotes.findByRef(blankToNull(raw.dispatchNoteNumber()))
                    .filter(n -> n.status == com.ntech.cabosse.dispatch.entity.DispatchNoteStatus.OPEN)
                    .map(n -> n.id).orElse(null);
            List<UUID> noteIds = new ArrayList<>();
            List<CommoditySaleUpsertDto.LoadDto> loadDtos = new ArrayList<>();
            if (headNoteId != null) {
                noteIds.add(headNoteId);
                loadDtos.add(new CommoditySaleUpsertDto.LoadDto(headNoteId,
                        blankToNull(raw.dispatchNoteNumber()), blankToNull(raw.loadingNumber()),
                        blankToNull(raw.departureLocation())));
            }
            for (LoadRow l : grouping.loadsByHead.getOrDefault(row.rowNumber(), List.of())) {
                if (l.note() == null) continue;
                noteIds.add(l.note().id);
                loadDtos.add(new CommoditySaleUpsertDto.LoadDto(l.note().id,
                        blankToNull(l.raw().dispatchNoteNumber()), blankToNull(l.raw().loadingNumber()),
                        blankToNull(l.raw().departureLocation())));
            }
            // Le grade annoncé « à créer » par l'aperçu s'ouvre ici, avant
            // l'écriture de la vente : sans lui, la création la refuserait
            // et la ligne repartirait écartée, ce que l'aperçu venait de
            // promettre qu'elle ne serait pas.
            qualityGrades.ensureForImport(blankToNull(raw.grade()));
            // Prix déduit du montant facturé (montant ÷ poids accepté).
            BigDecimal price = (nrm.amount() != null && nrm.acceptedKg() != null
                    && nrm.acceptedKg().signum() > 0)
                    ? nrm.amount().divide(nrm.acceptedKg(), 4, RoundingMode.HALF_UP) : null;
            try {
                var created = saleService.create(new CommoditySaleUpsertDto(
                        parseDate(raw.date()),
                        nrm.customerId(),
                        nrm.articleId(),
                        parseUuid(raw.siteId()),
                        parseUuid(raw.campaignId()),
                        blankToNull(raw.campaignType()),
                        null,
                        // Le bordereau nommé par le fichier, quand il existe.
                        // Sans ce rattachement, la vente redemandait une
                        // sortie de stock pour des kilos déjà sortis avec le
                        // chargement : « stock insuffisant, 0 disponible »
                        // pour une marchandise qui était bien partie.
                        headNoteId,
                        noteIds,
                        loadDtos,
                        new CommoditySaleUpsertDto.LogisticsDto(
                                blankToNull(raw.dispatchNoteNumber()), blankToNull(raw.loadingNumber()),
                                blankToNull(raw.departureLocation()), blankToNull(raw.destination()),
                                blankToNull(raw.connaissementRef()), blankToNull(raw.label()),
                                blankToNull(raw.originSections())),
                        new CommoditySaleUpsertDto.WeightsDto(
                                nrm.declaredKg(), parseDecimal(raw.dischargedKg()), nrm.acceptedKg(),
                                parseInt(raw.sacsAccepted()), parseInt(raw.sacsMissing()), parseInt(raw.sacsRejected())),
                        new CommoditySaleUpsertDto.RefactionsDto(
                                parseDecimal(raw.usineKg()), parseDecimal(raw.humidityKg()),
                                parseDecimal(raw.foreignMatterKg()), parseDecimal(raw.moldyKg()),
                                parseDecimal(raw.crabotsKg()), parseDecimal(raw.brokenKg()),
                                parseDecimal(raw.wasteKg()), parseDecimal(raw.otherKg())),
                        new CommoditySaleUpsertDto.QualityDto(
                                parseDecimal(raw.grainage()), parseDecimal(raw.moldyPct()),
                                parseDecimal(raw.slatePct()),
                                parseDecimal(raw.wastePct()), parseDecimal(raw.crabotsPct()),
                                parseDecimal(raw.fatContent()), parseDecimal(raw.fineness()),
                                parseDecimal(raw.purplePct()),
                                parseDecimal(raw.mitedPct()), parseDecimal(raw.flatPct()),
                                parseDecimal(raw.germinatedPct()), parseDecimal(raw.defectivePct()),
                                parseDecimal(raw.foreignMatterPct()), parseDecimal(raw.ffaPct()),
                                parseDecimal(raw.brokenPct()), parseDecimal(raw.humidityPct()),
                                blankToNull(raw.taste()), blankToNull(raw.grade()), blankToNull(raw.analysisResult())),
                        price, null, null, null, null,
                        new CommoditySaleUpsertDto.SettlementDto(
                                parseDecimal(raw.transportEqualization()),
                                parseDecimal(raw.gatheringDifferential()),
                                parseDecimal(raw.totalValue()),
                                parseDecimal(raw.bic()),
                                parseDecimal(raw.fiscalStamp()),
                                parseDecimal(raw.salesCommission()),
                                parseDecimal(raw.mandateRepayment()),
                                parseDecimal(raw.revolvingRepayment()),
                                parseDecimal(raw.collectorRetention()),
                                parseDecimal(raw.totalDeductions()),
                                parseDecimal(raw.netAmount()))));
                createdRefs.add(created.ref());
            } catch (RuntimeException e) {
                skipped.add(new Row(row.rowNumber(), Status.INVALID, nrm,
                        List.of(new FieldIssue("_", explain(e, raw, nrm)))));
            }
        }
        return new CommoditySaleImportCommitResponseDto(
                preview.totalRows(), createdRefs.size(), skipped.size(), createdRefs, skipped);
    }

    /** Un chargement de plus, lu sur sa ligne, et le bordereau qu'il appelle. */
    private record LoadRow(CommoditySaleImportRowDto raw, int headRowNumber,
                           com.ntech.cabosse.dispatch.entity.DispatchNoteEntity note, String error) {}

    /** Les chargements rangés sous leur vente, et ceux qui n'en ont trouvé aucune. */
    private record Grouping(Map<Integer, LoadRow> loads, Map<Integer, List<LoadRow>> loadsByHead,
                            Set<Integer> orphans) {}

    /**
     * Une vente qui réunit le cacao de plusieurs magasins s'écrit sur
     * plusieurs lignes : une par bordereau de sortie (N° BS, N° chargement,
     * lieu de départ, poids déclaré), et une seule qui porte le reste
     * (poids déchargé et accepté, sacs, réfactions, montants), la vente
     * elle-même. Une ligne qui n'a qu'un chargement rejoint la vente la
     * plus proche du même client, du même produit et du même jour, qu'elle
     * la précède ou la suive ; sans vente à rejoindre, elle est refusée.
     */
    private Grouping group(List<CommoditySaleImportRowDto> input) {
        Map<Integer, LoadRow> loads = new HashMap<>();
        Map<Integer, List<LoadRow>> loadsByHead = new HashMap<>();
        Set<Integer> orphans = new HashSet<>();
        List<CommoditySaleImportRowDto> pending = new ArrayList<>();
        CommoditySaleImportRowDto lastHead = null;
        for (CommoditySaleImportRowDto raw : input) {
            if (isLoadOnly(raw)) {
                if (lastHead != null && sameSale(raw, lastHead)) {
                    attach(raw, lastHead, loads, loadsByHead);
                } else {
                    if (!pending.isEmpty() && !sameSale(raw, pending.get(0))) {
                        pending.forEach(p -> orphans.add(p.rowNumber()));
                        pending.clear();
                    }
                    pending.add(raw);
                }
                continue;
            }
            for (CommoditySaleImportRowDto p : pending) {
                if (sameSale(p, raw)) attach(p, raw, loads, loadsByHead);
                else orphans.add(p.rowNumber());
            }
            pending.clear();
            lastHead = raw;
        }
        pending.forEach(p -> orphans.add(p.rowNumber()));
        return new Grouping(loads, loadsByHead, orphans);
    }

    private void attach(CommoditySaleImportRowDto load, CommoditySaleImportRowDto head,
                        Map<Integer, LoadRow> loads, Map<Integer, List<LoadRow>> loadsByHead) {
        String ref = blankToNull(load.dispatchNoteNumber());
        var note = dispatchNotes.findByRef(ref)
                .filter(n -> n.status == com.ntech.cabosse.dispatch.entity.DispatchNoteStatus.OPEN);
        LoadRow row = new LoadRow(load, head.rowNumber(), note.orElse(null),
                note.isPresent() ? null : Messages.msg("m.imp-load-note-missing", ref));
        loads.put(load.rowNumber(), row);
        loadsByHead.computeIfAbsent(head.rowNumber(), k -> new ArrayList<>()).add(row);
    }

    /** Un bordereau et un poids déclaré, et rien de ce qui fait la vente. */
    private static boolean isLoadOnly(CommoditySaleImportRowDto r) {
        return blankToNull(r.dispatchNoteNumber()) != null
                && blankToNull(r.declaredKg()) != null
                && blankToNull(r.acceptedKg()) == null
                && blankToNull(r.dischargedKg()) == null
                && blankToNull(r.montantFacture()) == null;
    }

    /** Même client, même produit et même jour ; une case vide sur le chargement hérite de la vente. */
    private static boolean sameSale(CommoditySaleImportRowDto load, CommoditySaleImportRowDto head) {
        return inherits(load.customerName(), head.customerName())
                && inherits(load.productCode(), head.productCode())
                && inherits(load.date(), head.date());
    }

    private static boolean inherits(String value, String reference) {
        return blankToNull(value) == null
                || ImportFields.normalize(value).equals(ImportFields.normalize(reference));
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim().replace(" ", "").replace(",", ".")); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim().replace(" ", "")); } catch (Exception e) { return null; }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    /**
     * Le refus, complété de ce qu'il ne dit pas.
     *
     * <p>« Stock insuffisant : 0 disponible » est exact et inutile quand
     * la marchandise vient de partir sur un chargement : le stock est à
     * zéro parce qu'elle est sortie, pas parce qu'elle manque. On nomme
     * alors le bordereau, et la colonne qui permet de l'appeler.</p>
     */
    private String explain(RuntimeException e, CommoditySaleImportRowDto raw, Normalized nrm) {
        String message = com.ntech.cabosse.shared.exception.UserFacingReason.of(e);
        boolean outOfStock = e instanceof com.ntech.cabosse.shared.exception.BusinessException be
                && be.errorCode() == com.ntech.cabosse.shared.exception.ErrorCode.STOCK_INSUFFICIENT;
        if (!outOfStock) return message;
        var open = dispatchNotes.listOpenFor(parseUuid(raw.siteId()), nrm.articleId());
        if (open.isEmpty()) return message;
        String refs = open.stream().map(n -> n.ref).collect(java.util.stream.Collectors.joining(", "));
        return message + " " + Messages.msg("m.imp-stock-left-on-note", refs);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
