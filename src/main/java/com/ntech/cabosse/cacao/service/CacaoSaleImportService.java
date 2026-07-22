package com.ntech.cabosse.cacao.service;

import com.ntech.cabosse.cacao.dto.CacaoSaleImportCommitResponseDto;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportPreviewDto;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportPreviewDto.FieldIssue;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportPreviewDto.Normalized;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportPreviewDto.Row;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportPreviewDto.Status;
import com.ntech.cabosse.cacao.dto.CacaoSaleImportRowDto;
import com.ntech.cabosse.cacao.dto.CacaoSaleUpsertDto;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantProduct;
import com.ntech.cabosse.tenant.repository.TenantRepository;
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
 * poids accepté}. Le commit délègue à {@link CacaoSaleService#create}.
 */
@ApplicationScoped
public class CacaoSaleImportService {

    @Inject CustomerRepository customers;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;
    @Inject CacaoSaleService saleService;

    public CacaoSaleImportPreviewDto preview(List<CacaoSaleImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new CacaoSaleImportPreviewDto(0, 0, 0, List.of());
        }
        Map<String, CustomerEntity> byName = new HashMap<>();
        for (CustomerEntity c : customers.listAll()) {
            if (c.name != null) byName.putIfAbsent(c.name.trim().toUpperCase(Locale.ROOT), c);
        }
        Map<String, TenantProduct> productsByKey = new HashMap<>();
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        if (tenant != null && tenant.productsSold != null) {
            for (TenantProduct pr : tenant.productsSold) {
                if (pr.code != null && !pr.code.isBlank())
                    productsByKey.putIfAbsent(pr.code.trim().toUpperCase(Locale.ROOT), pr);
                if (pr.label != null && !pr.label.isBlank())
                    productsByKey.putIfAbsent(pr.label.trim().toUpperCase(Locale.ROOT), pr);
            }
        }

        List<Row> rows = new ArrayList<>();
        int ready = 0, invalid = 0;
        for (CacaoSaleImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            CustomerEntity customer = raw.customerName() == null || raw.customerName().isBlank()
                    ? null : byName.get(raw.customerName().trim().toUpperCase(Locale.ROOT));
            if (customer == null) issues.add(new FieldIssue("customerName", "Client introuvable (nom)."));

            TenantProduct product = raw.productCode() == null || raw.productCode().isBlank()
                    ? null : productsByKey.get(raw.productCode().trim().toUpperCase(Locale.ROOT));
            if (product == null) issues.add(new FieldIssue("productCode", "Produit inconnu de la coopérative."));
            else if (product.articleId == null) issues.add(new FieldIssue("productCode", "Produit non rattaché à un article."));

            LocalDate date = parseDate(raw.date());
            if (date == null) issues.add(new FieldIssue("date", "Date invalide ou manquante."));
            if (parseUuid(raw.siteId()) == null) issues.add(new FieldIssue("siteId", "Site (départ) requis."));

            BigDecimal declared = parseDecimal(raw.declaredKg());
            BigDecimal accepted = parseDecimal(raw.acceptedKg());
            if (declared == null || declared.signum() <= 0)
                issues.add(new FieldIssue("declaredKg", "Poids déclaré requis."));
            if (accepted == null || accepted.signum() <= 0)
                issues.add(new FieldIssue("acceptedKg", "Poids accepté requis."));

            BigDecimal montant = parseDecimal(raw.montantFacture());

            Status status = issues.isEmpty() ? Status.READY : Status.INVALID;
            if (status == Status.READY) ready++; else invalid++;

            rows.add(new Row(raw.rowNumber(), status,
                    new Normalized(
                            customer != null ? customer.id : null,
                            customer != null ? customer.name : null,
                            product != null ? product.code : null,
                            product != null ? product.label : null,
                            date != null ? date.toString() : null,
                            declared, accepted, montant),
                    issues));
        }
        return new CacaoSaleImportPreviewDto(input.size(), ready, invalid, rows);
    }

    public CacaoSaleImportCommitResponseDto commit(List<CacaoSaleImportRowDto> input) {
        CacaoSaleImportPreviewDto preview = preview(input);
        Map<Integer, CacaoSaleImportRowDto> byRow = new HashMap<>();
        if (input != null) input.forEach(r -> byRow.put(r.rowNumber(), r));

        List<String> createdRefs = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) { skipped.add(row); continue; }
            CacaoSaleImportRowDto raw = byRow.get(row.rowNumber());
            Normalized nrm = row.normalized();
            // Prix déduit du montant facturé (montant ÷ poids accepté).
            BigDecimal price = (nrm.amountFcfa() != null && nrm.acceptedKg() != null
                    && nrm.acceptedKg().signum() > 0)
                    ? nrm.amountFcfa().divide(nrm.acceptedKg(), 4, RoundingMode.HALF_UP) : null;
            try {
                var created = saleService.create(new CacaoSaleUpsertDto(
                        parseDate(raw.date()),
                        nrm.customerId(),
                        nrm.productCode(),
                        parseUuid(raw.siteId()),
                        parseUuid(raw.campaignId()),
                        blankToNull(raw.campaignType()),
                        null,
                        new CacaoSaleUpsertDto.LogisticsDto(
                                blankToNull(raw.departureLocation()), blankToNull(raw.destination()),
                                blankToNull(raw.connaissementRef()), blankToNull(raw.label()),
                                blankToNull(raw.originSections())),
                        new CacaoSaleUpsertDto.WeightsDto(
                                nrm.declaredKg(), parseDecimal(raw.dischargedKg()), nrm.acceptedKg(),
                                parseInt(raw.sacsAccepted()), parseInt(raw.sacsMissing()), parseInt(raw.sacsRejected())),
                        new CacaoSaleUpsertDto.RefactionsDto(
                                parseDecimal(raw.usineKg()), parseDecimal(raw.humidityKg()),
                                parseDecimal(raw.foreignMatterKg()), parseDecimal(raw.moldyKg()),
                                parseDecimal(raw.crabotsKg()), parseDecimal(raw.brokenKg()),
                                parseDecimal(raw.wasteKg()), parseDecimal(raw.otherKg())),
                        new CacaoSaleUpsertDto.QualityDto(
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
        return new CacaoSaleImportCommitResponseDto(
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
