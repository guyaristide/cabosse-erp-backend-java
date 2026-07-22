package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportCommitResponseDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.FieldIssue;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Normalized;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Row;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Status;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportRowDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.entity.TenantProduct;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Import de masse des reçus d'achat producteur (backlog NEG-01). Rapproche
 * chaque ligne au producteur (N° carte CCC ou N° interne) et au produit
 * (liste de la coopérative, rattaché à un article). Le commit délègue à
 * {@link ProducerPurchaseService#create} (stock + écriture par reçu).
 */
@ApplicationScoped
public class ProducerPurchaseImportService {

    @Inject MemberRepository members;
    @Inject TenantRepository tenants;
    @Inject TenantContext tenantContext;
    @Inject TenantPreferencesLookup preferences;
    @Inject ProducerPurchaseService purchaseService;

    public ProducerPurchaseImportPreviewDto preview(List<ProducerPurchaseImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new ProducerPurchaseImportPreviewDto(0, 0, 0, List.of());
        }
        TenantPreferences prefs = preferences.current();
        boolean fromBags = TenantPreferences.PRODUCER_WEIGHT_FROM_BAGS.equals(prefs.producerWeightMode());
        boolean priceManual = TenantPreferences.PRODUCER_PRICE_MANUAL.equals(prefs.producerPriceSource());
        boolean amountManual = TenantPreferences.PRODUCER_AMOUNT_MANUAL.equals(prefs.producerAmountMode());

        Map<String, MemberEntity> byCode = new HashMap<>();
        Map<String, MemberEntity> byExternal = new HashMap<>();
        for (MemberEntity m : members.listAll()) {
            if (m.code != null) byCode.putIfAbsent(m.code.trim().toUpperCase(Locale.ROOT), m);
            if (m.externalProducerCodes != null) {
                m.externalProducerCodes.stream()
                        .filter(c -> c.number != null && !c.number.isBlank())
                        .forEach(c -> byExternal.putIfAbsent(c.number.trim().toUpperCase(Locale.ROOT), m));
            }
        }

        // Indexé par code ET par libellé : le fichier reçu porte souvent le
        // libellé (« cacao ») plutôt que le code.
        Map<String, TenantProduct> productsByCode = new HashMap<>();
        TenantEntity tenant = tenants.findById(tenantContext.tenantId());
        if (tenant != null && tenant.productsSold != null) {
            for (TenantProduct pr : tenant.productsSold) {
                if (pr.code != null && !pr.code.isBlank()) {
                    productsByCode.putIfAbsent(pr.code.trim().toUpperCase(Locale.ROOT), pr);
                }
                if (pr.label != null && !pr.label.isBlank()) {
                    productsByCode.putIfAbsent(pr.label.trim().toUpperCase(Locale.ROOT), pr);
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        int ready = 0, invalid = 0;
        for (ProducerPurchaseImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();

            MemberEntity member = matchMember(raw.producerRef(), byCode, byExternal);
            if (member == null) {
                issues.add(new FieldIssue("producerRef", "Producteur introuvable (N° carte CCC ou N° interne)."));
            }

            TenantProduct product = raw.productCode() == null || raw.productCode().isBlank()
                    ? null : productsByCode.get(raw.productCode().trim().toUpperCase(Locale.ROOT));
            if (product == null) {
                issues.add(new FieldIssue("productCode", "Produit inconnu de la coopérative."));
            } else if (product.articleId == null) {
                issues.add(new FieldIssue("productCode", "Produit non rattaché à un article."));
            }

            LocalDate date = parseDate(raw.date());
            if (date == null) issues.add(new FieldIssue("date", "Date invalide ou manquante."));

            if (parseUuid(raw.siteId()) == null) {
                issues.add(new FieldIssue("siteId", "Site d'entrée requis."));
            }

            BigDecimal weight = parseDecimal(raw.weightKg());
            Integer sacs = parseInt(raw.nbSacs());
            // Validation alignée sur les préférences tenant (comme le commit).
            if (fromBags) {
                if (sacs == null || sacs <= 0) {
                    issues.add(new FieldIssue("nbSacs", "Mode « poids dérivé des sacs » : nombre de sacs requis."));
                }
                if (prefs.producerStandardBagKg == null) {
                    issues.add(new FieldIssue("nbSacs", "Poids standard du sac non configuré (admin)."));
                }
            } else if (weight == null || weight.signum() <= 0) {
                issues.add(new FieldIssue("weightKg", "Poids (kg) requis."));
            }

            BigDecimal price = parseDecimal(raw.price());
            BigDecimal amount = parseDecimal(raw.amount());
            if (priceManual && (price == null || price.signum() < 0)) {
                issues.add(new FieldIssue("price", "Prix garanti requis (paramétrage : saisie manuelle)."));
            }
            if (amountManual && (amount == null || amount.signum() <= 0)) {
                issues.add(new FieldIssue("amount", "Montant requis (paramétrage : montant saisi)."));
            }
            if (parsePayment(raw.paymentMethod()) == null) {
                issues.add(new FieldIssue("paymentMethod", "Mode de paiement invalide."));
            }

            Status status = issues.isEmpty() ? Status.READY : Status.INVALID;
            if (status == Status.READY) ready++; else invalid++;

            BigDecimal displayAmount = amount != null ? amount
                    : (weight != null && price != null ? weight.multiply(price) : null);
            Normalized normalized = new Normalized(
                    member != null ? member.id : null,
                    member != null ? member.name : null,
                    product != null ? product.code : null,
                    product != null ? product.label : null,
                    date != null ? date.toString() : null,
                    sacs, weight, price, displayAmount,
                    normalizePayment(raw.paymentMethod()));
            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        return new ProducerPurchaseImportPreviewDto(input.size(), ready, invalid, rows);
    }

    public ProducerPurchaseImportCommitResponseDto commit(List<ProducerPurchaseImportRowDto> input) {
        ProducerPurchaseImportPreviewDto preview = preview(input);
        Map<Integer, ProducerPurchaseImportRowDto> byRow = new HashMap<>();
        if (input != null) input.forEach(r -> byRow.put(r.rowNumber(), r));

        List<String> createdRefs = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            if (row.status() != Status.READY || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            ProducerPurchaseImportRowDto raw = byRow.get(row.rowNumber());
            Normalized n = row.normalized();
            try {
                var created = purchaseService.create(new ProducerPurchaseUpsertDto(
                        parseDate(raw.date()),
                        n.memberId(),
                        n.productCode(),
                        parseUuid(raw.siteId()),
                        parseUuid(raw.campaignId()),
                        parseInt(raw.nbSacs()),
                        parseDecimal(raw.weightKg()),
                        parseDecimal(raw.price()),
                        parseDecimal(raw.amount()),
                        parsePayment(raw.paymentMethod()),
                        blankToNull(raw.paymentRef()),
                        null,
                        blankToNull(raw.payerName()),
                        null));
                createdRefs.add(created.ref());
            } catch (RuntimeException e) {
                skipped.add(new Row(row.rowNumber(), Status.INVALID, n,
                        List.of(new FieldIssue("_", e.getMessage()))));
            }
        }
        return new ProducerPurchaseImportCommitResponseDto(
                preview.totalRows(), createdRefs.size(), skipped.size(), createdRefs, skipped);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static MemberEntity matchMember(String ref, Map<String, MemberEntity> byCode,
                                            Map<String, MemberEntity> byExternal) {
        if (ref == null || ref.isBlank()) return null;
        String key = ref.trim().toUpperCase(Locale.ROOT);
        MemberEntity m = byCode.get(key);
        return m != null ? m : byExternal.get(key);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim().replace(" ", "").replace(",", "."));
        } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim().replace(" ", "")); } catch (Exception e) { return null; }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    private static PaymentMethod parsePayment(String s) {
        if (s == null || s.isBlank()) return PaymentMethod.CASH;
        try { return PaymentMethod.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    private static String normalizePayment(String s) {
        PaymentMethod pm = parsePayment(s);
        return pm != null ? pm.name() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
