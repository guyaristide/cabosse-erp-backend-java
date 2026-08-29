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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Import de masse des ventes cacao (backlog NEG-02). Rapproche le client par
 * nom et le produit (code ou libellé) ; le prix dérive de {@code montantFacture ÷
 * poids accepté}. Le commit délègue à {@link CommoditySaleService#create}.
 */
@ApplicationScoped
public class CommoditySaleImportService {

    @Inject CustomerRepository customers;
    @Inject ArticleRepository articles;
    @Inject CommoditySaleService saleService;

    public CommoditySaleImportPreviewDto preview(List<CommoditySaleImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new CommoditySaleImportPreviewDto(0, 0, 0, List.of());
        }
        Map<String, CustomerEntity> byName = new HashMap<>();
        for (CustomerEntity c : customers.listAll()) {
            if (c.name != null) byName.putIfAbsent(c.name.trim().toUpperCase(Locale.ROOT), c);
        }
        // Articles vendables, rapprochés par code OU nom.
        Map<String, ArticleEntity> articlesByKey = new HashMap<>();
        for (ArticleEntity a : articles.listAll()) {
            if (!a.sellable || !a.active) continue;
            if (a.code != null && !a.code.isBlank())
                articlesByKey.putIfAbsent(a.code.trim().toUpperCase(Locale.ROOT), a);
            if (a.name != null && !a.name.isBlank())
                articlesByKey.putIfAbsent(a.name.trim().toUpperCase(Locale.ROOT), a);
        }

        List<Row> rows = new ArrayList<>();
        int ready = 0, invalid = 0;
        for (CommoditySaleImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            CustomerEntity customer = raw.customerName() == null || raw.customerName().isBlank()
                    ? null : byName.get(raw.customerName().trim().toUpperCase(Locale.ROOT));
            if (customer == null) issues.add(new FieldIssue("customerName", Messages.msg("m.imp-customer-not-found")));

            ArticleEntity article = raw.productCode() == null || raw.productCode().isBlank()
                    ? null : articlesByKey.get(raw.productCode().trim().toUpperCase(Locale.ROOT));
            if (article == null) issues.add(new FieldIssue("productCode", Messages.msg("m.imp-sellable-article-unknown")));

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
                    issues));
        }
        return new CommoditySaleImportPreviewDto(input.size(), ready, invalid, rows);
    }

    public CommoditySaleImportCommitResponseDto commit(List<CommoditySaleImportRowDto> input) {
        CommoditySaleImportPreviewDto preview = preview(input);
        Map<Integer, CommoditySaleImportRowDto> byRow = new HashMap<>();
        if (input != null) input.forEach(r -> byRow.put(r.rowNumber(), r));

        List<String> createdRefs = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) { skipped.add(row); continue; }
            CommoditySaleImportRowDto raw = byRow.get(row.rowNumber());
            Normalized nrm = row.normalized();
            // Prix déduit du montant facturé (montant ÷ poids accepté).
            BigDecimal price = (nrm.amountFcfa() != null && nrm.acceptedKg() != null
                    && nrm.acceptedKg().signum() > 0)
                    ? nrm.amountFcfa().divide(nrm.acceptedKg(), 4, RoundingMode.HALF_UP) : null;
            try {
                var created = saleService.create(new CommoditySaleUpsertDto(
                        parseDate(raw.date()),
                        nrm.customerId(),
                        nrm.articleId(),
                        parseUuid(raw.siteId()),
                        parseUuid(raw.campaignId()),
                        blankToNull(raw.campaignType()),
                        null,
                        new CommoditySaleUpsertDto.LogisticsDto(
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
                                parseDecimal(raw.slatePct()), parseDecimal(raw.purplePct()),
                                parseDecimal(raw.mitedPct()), parseDecimal(raw.flatPct()),
                                parseDecimal(raw.germinatedPct()), parseDecimal(raw.defectivePct()),
                                parseDecimal(raw.foreignMatterPct()), parseDecimal(raw.ffaPct()),
                                parseDecimal(raw.brokenPct()), parseDecimal(raw.humidityPct()),
                                blankToNull(raw.taste()), blankToNull(raw.grade()), blankToNull(raw.analysisResult())),
                        price, null, null, null, null));
                createdRefs.add(created.ref());
            } catch (RuntimeException e) {
                skipped.add(new Row(row.rowNumber(), Status.INVALID, nrm,
                        List.of(new FieldIssue("_", e.getMessage()))));
            }
        }
        return new CommoditySaleImportCommitResponseDto(
                preview.totalRows(), createdRefs.size(), skipped.size(), createdRefs, skipped);
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

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
