package com.ntech.cabosse.producerpurchase.service;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.members.service.ProducerLookup;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportCommitResponseDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.FieldIssue;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Normalized;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Row;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportPreviewDto.Status;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseImportRowDto;
import com.ntech.cabosse.producerpurchase.dto.ProducerPurchaseUpsertDto;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.collector.repository.CollectorAdvanceRepository;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.imports.FuzzyLabels;
import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.supplier.repository.SupplierRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
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
    @Inject ProducerLookup producerLookup;
    @Inject ArticleRepository articles;
    @Inject SupplierRepository suppliers;
    @Inject CampaignRepository campaigns;
    @Inject CollectorAdvanceRepository advances;
    @Inject TenantPreferencesLookup preferences;
    @Inject ProducerPurchaseService purchaseService;
    @Inject com.ntech.cabosse.suppliercategory.service.SupplierMarginResolver marginResolver;
    @Inject DeliveryNoteRefService deliveryNoteRefService;

    public ProducerPurchaseImportPreviewDto preview(List<ProducerPurchaseImportRowDto> input) {
        if (input == null || input.isEmpty()) {
            return new ProducerPurchaseImportPreviewDto(0, 0, 0, 0, List.of(), List.of());
        }
        TenantPreferences prefs = preferences.current();
        boolean fromBags = TenantPreferences.PRODUCER_WEIGHT_FROM_BAGS.equals(prefs.producerWeightMode());
        boolean priceManual = TenantPreferences.PRODUCER_PRICE_MANUAL.equals(prefs.producerPriceSource());
        boolean amountManual = TenantPreferences.PRODUCER_AMOUNT_MANUAL.equals(prefs.producerAmountMode());

        ProducerLookup.Index producers = producerLookup.index();

        // Indexé par code ET par libellé : le fichier reçu porte souvent le
        // libellé (« cacao ») plutôt que le code.
        Map<String, ArticleEntity> articlesByKey = new HashMap<>();
        for (ArticleEntity a : articles.listAll()) {
            if (!a.purchasable || !a.active) continue;
            if (a.code != null && !a.code.isBlank()) {
                articlesByKey.putIfAbsent(a.code.trim().toUpperCase(Locale.ROOT), a);
            }
            if (a.name != null && !a.name.isBlank()) {
                articlesByKey.putIfAbsent(a.name.trim().toUpperCase(Locale.ROOT), a);
            }
        }

        List<SupplierEntity> delegates = suppliers.listAll().stream()
                .filter(x -> x.collector && x.active).toList();
        List<CampaignEntity> allCampaigns = campaigns.listAll();

        // Solde de départ de chaque délégué : ce qu'il doit encore livrer,
        // tous ses engagements ouverts confondus.
        Map<UUID, BigDecimal> balanceBefore = new HashMap<>();
        Map<UUID, BigDecimal> running = new HashMap<>();
        Map<UUID, DelegateAccumulator> perDelegate = new LinkedHashMap<>();

        List<Row> rows = new ArrayList<>();
        int ready = 0, warning = 0, invalid = 0;
        for (ProducerPurchaseImportRowDto raw : input) {
            List<FieldIssue> issues = new ArrayList<>();
            boolean blocking = false;

            ProducerLookup.Match match = producers.resolve(raw.producerRef(), raw.producerCardRef());
            MemberEntity member = match.member();
            if (member == null) {
                issues.add(new FieldIssue("producerRef", match.failure()));
                blocking = true;
            } else if (nameDiverges(raw.producerName(), member.name)) {
                issues.add(new FieldIssue("producerName",
                        Messages.msg("m.imp-name-mismatch-number-wins", member.name)));
            }

            ArticleEntity article = raw.productCode() == null || raw.productCode().isBlank()
                    ? null : articlesByKey.get(raw.productCode().trim().toUpperCase(Locale.ROOT));
            if (article == null) {
                issues.add(new FieldIssue("productCode", Messages.msg("m.imp-purchasable-article-unknown")));
                blocking = true;
            }

            LocalDate date = parseDate(raw.date());
            if (date == null) {
                issues.add(new FieldIssue("date", Messages.msg("m.imp-date-invalid-or-missing")));
                blocking = true;
            }

            if (parseUuid(raw.siteId()) == null) {
                issues.add(new FieldIssue("siteId", Messages.msg("m.imp-entry-site-required")));
                blocking = true;
            }

            CampaignEntity campaign = resolveCampaign(raw, allCampaigns);
            if (campaign == null && raw.campaignLabel() != null && !raw.campaignLabel().isBlank()) {
                issues.add(new FieldIssue("campaignLabel",
                        Messages.msg("m.imp-campaign-unknown", raw.campaignLabel().trim())));
            }

            BigDecimal weight = parseDecimal(raw.weightKg());
            Integer sacs = parseInt(raw.nbSacs());
            // Validation alignée sur les préférences tenant (comme le commit).
            if (fromBags) {
                if (sacs == null || sacs <= 0) {
                    issues.add(new FieldIssue("nbSacs", Messages.msg("m.imp-bags-count-required")));
                    blocking = true;
                }
                if (prefs.producerStandardBagKg == null) {
                    issues.add(new FieldIssue("nbSacs", Messages.msg("m.imp-standard-bag-weight-missing")));
                    blocking = true;
                } else if (sacs != null && sacs > 0) {
                    weight = prefs.producerStandardBagKg.multiply(BigDecimal.valueOf(sacs));
                }
            } else if (weight == null || weight.signum() <= 0) {
                issues.add(new FieldIssue("weightKg", Messages.msg("m.imp-weight-kg-required")));
                blocking = true;
            }

            BigDecimal price = parseDecimal(raw.price());
            BigDecimal amount = parseDecimal(raw.amount());
            if (priceManual && (price == null || price.signum() < 0)) {
                issues.add(new FieldIssue("price", Messages.msg("m.imp-guaranteed-price-required")));
                blocking = true;
            }
            if (amountManual && (amount == null || amount.signum() <= 0)) {
                issues.add(new FieldIssue("amount", Messages.msg("m.imp-amount-required")));
                blocking = true;
            }
            if (price == null && campaign != null) price = campaign.basePricePerKg;
            BigDecimal displayAmount = amount != null ? amount
                    : (weight != null && price != null ? weight.multiply(price) : null);

            BigDecimal paid = parseDecimal(raw.amountPaid());
            if (paid != null && displayAmount != null && paid.compareTo(displayAmount) > 0) {
                issues.add(new FieldIssue("amountPaid", Messages.msg("m.imp-paid-over-due")));
                blocking = true;
            }
            if (paid != null && displayAmount != null && paid.compareTo(displayAmount) < 0
                    && !prefs.producerPartialPaymentEnabled()) {
                issues.add(new FieldIssue("amountPaid",
                        Messages.msg("m.imp-partial-payment-not-allowed")));
            }

            if (parsePayment(raw.paymentMethod()) == null) {
                issues.add(new FieldIssue("paymentMethod", Messages.msg("m.imp-payment-method-invalid")));
                blocking = true;
            }

            SupplierEntity delegate = matchDelegate(raw.delegateCode(), raw.delegateName(), delegates);
            if (delegate == null && (notBlank(raw.delegateCode()) || notBlank(raw.delegateName()))) {
                issues.add(new FieldIssue("delegateCode",
                        Messages.msg("m.imp-delegate-not-found")));
            } else if (delegate != null && notBlank(raw.delegateName())
                    && nameDiverges(raw.delegateName(), delegate.name)) {
                issues.add(new FieldIssue("delegateName",
                        Messages.msg("m.imp-name-mismatch-code-wins", delegate.name)));
            }

            Status status;
            if (blocking) {
                status = Status.INVALID;
                invalid++;
            } else if (!issues.isEmpty()) {
                status = Status.WARNING;
                warning++;
            } else {
                status = Status.READY;
                ready++;
            }

            // Cumul de l'apurement, sur les seules lignes applicables.
            if (status != Status.INVALID && delegate != null && displayAmount != null && weight != null) {
                BigDecimal margin = marginResolver
                        .resolve(prefs, delegate, campaign != null ? campaign.id : null)
                        .on(weight, displayAmount);
                balanceBefore.computeIfAbsent(delegate.id, this::openBalance);
                running.merge(delegate.id, displayAmount.add(margin), BigDecimal::add);
                perDelegate.computeIfAbsent(delegate.id, k -> new DelegateAccumulator(delegate.name))
                        .add(weight, displayAmount, margin);
            }

            Normalized normalized = new Normalized(
                    member != null ? member.id : null,
                    member != null ? member.name : null,
                    article != null ? article.id : null,
                    article != null ? article.name : null,
                    date != null ? date.toString() : null,
                    blankToNull(raw.officialReceiptRef()),
                    sacs, weight, price, displayAmount, paid,
                    normalizePayment(raw.paymentMethod()),
                    delegate != null ? delegate.id : null,
                    delegate != null ? delegate.name : null,
                    campaign != null ? campaign.label : null);
            rows.add(new Row(raw.rowNumber(), status, normalized, issues));
        }

        List<ProducerPurchaseImportPreviewDto.DelegateSummary> summaries = new ArrayList<>();
        for (Map.Entry<UUID, DelegateAccumulator> en : perDelegate.entrySet()) {
            BigDecimal before = balanceBefore.getOrDefault(en.getKey(), BigDecimal.ZERO);
            BigDecimal applied = running.getOrDefault(en.getKey(), BigDecimal.ZERO);
            DelegateAccumulator acc = en.getValue();
            summaries.add(new ProducerPurchaseImportPreviewDto.DelegateSummary(
                    en.getKey(), acc.name, acc.count, acc.weight, acc.amount, acc.margin,
                    before, before.subtract(applied)));
        }

        return new ProducerPurchaseImportPreviewDto(
                input.size(), ready, warning, invalid, summaries, rows);
    }

    /** Ce que le délégué doit encore livrer, toutes avances ouvertes confondues. */
    private BigDecimal openBalance(UUID delegateSupplierId) {
        return advances.listOpenByDelegate(delegateSupplierId).stream()
                .map(a -> a.remaining != null ? a.remaining : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class DelegateAccumulator {
        private final String name;
        private int count;
        private BigDecimal weight = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal margin = BigDecimal.ZERO;

        private DelegateAccumulator(String name) { this.name = name; }

        private void add(BigDecimal w, BigDecimal a, BigDecimal m) {
            count++;
            weight = weight.add(w);
            amount = amount.add(a);
            margin = margin.add(m);
        }
    }

    /**
     * @param includeWarnings applique aussi les lignes signalées (nom qui
     *                        diverge, délégué introuvable, campagne inconnue)
     */
    public ProducerPurchaseImportCommitResponseDto commit(List<ProducerPurchaseImportRowDto> input,
                                                          boolean includeWarnings) {
        ProducerPurchaseImportPreviewDto preview = preview(input);
        Map<Integer, ProducerPurchaseImportRowDto> byRow = new HashMap<>();
        if (input != null) input.forEach(r -> byRow.put(r.rowNumber(), r));
        List<CampaignEntity> allCampaigns = campaigns.listAll();

        // Un bordereau par délégué et par date : c'est ce qu'il apporte en
        // une fois. Les reçus sans délégué n'en portent pas.
        Map<String, String> deliveryRefs = new HashMap<>();

        List<String> createdRefs = new ArrayList<>();
        List<Row> skipped = new ArrayList<>();
        for (Row row : preview.rows()) {
            boolean applicable = row.status() == Status.READY
                    || (row.status() == Status.WARNING && includeWarnings);
            if (!applicable || row.normalized() == null) {
                skipped.add(row);
                continue;
            }
            ProducerPurchaseImportRowDto raw = byRow.get(row.rowNumber());
            Normalized n = row.normalized();
            try {
                CampaignEntity campaign = resolveCampaign(raw, allCampaigns);
                String deliveryRef = null;
                if (n.delegateSupplierId() != null) {
                    deliveryRef = deliveryRefs.computeIfAbsent(
                            n.delegateSupplierId() + "|" + n.date(),
                            k -> deliveryNoteRefService.next());
                }
                var created = purchaseService.create(new ProducerPurchaseUpsertDto(
                        parseDate(raw.date()),
                        blankToNull(raw.officialReceiptRef()),
                        blankToNull(raw.producerCardRef() != null && !raw.producerCardRef().isBlank()
                                ? raw.producerCardRef() : raw.producerRef()),
                        n.memberId(),
                        n.articleId(),
                        parseUuid(raw.siteId()),
                        campaign != null ? campaign.id : parseUuid(raw.campaignId()),
                        // Le fichier d'import ne porte ni camion ni détail
                        // des pesées : c'est une reprise, pas une bascule.
                        null,
                        null,
                        parseInt(raw.nbSacs()),
                        parseDecimal(raw.weightKg()),
                        parseDecimal(raw.price()),
                        parseDecimal(raw.amount()),
                        parseDecimal(raw.amountPaid()),
                        parsePayment(raw.paymentMethod()),
                        // L'import ne désigne pas de caisse : le fichier ne
                        // dit pas par quel tiroir l'argent est passé. Le
                        // compte par défaut du mode de paiement s'applique.
                        null,
                        blankToNull(raw.paymentRef()),
                        null,
                        n.delegateSupplierId() == null ? blankToNull(raw.delegateName()) : null,
                        n.delegateSupplierId(),
                        deliveryRef,
                        // L'import ne décide aucune retenue : elle se
                        // discute avec le producteur, pas dans un fichier.
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

    /**
     * Délégué du fichier : le code tranche, le nom ne sert qu'à contrôler.
     * Un nom seul reste accepté quand il désigne sans ambiguïté un délégué
     * connu, parce que les fichiers de terrain n'ont souvent que ça.
     */
    private static SupplierEntity matchDelegate(String rawCode, String rawName,
                                                List<SupplierEntity> delegates) {
        String code = blankToNull(rawCode);
        if (code != null) {
            for (SupplierEntity d : delegates) {
                if (d.code != null && d.code.trim().equalsIgnoreCase(code)) return d;
            }
        }
        String name = blankToNull(rawName);
        if (name == null) return null;
        String canonical = FuzzyLabels.canonical(name);
        List<SupplierEntity> exact = delegates.stream()
                .filter(d -> FuzzyLabels.canonical(d.name).equals(canonical)).toList();
        if (exact.size() == 1) return exact.get(0);
        if (!exact.isEmpty()) return null;
        List<SupplierEntity> close = delegates.stream()
                .filter(d -> FuzzyLabels.matches(d.name, name)).toList();
        return close.size() == 1 ? close.get(0) : null;
    }

    /** Campagne portée par la ligne, sinon celle choisie à l'écran. */
    private static CampaignEntity resolveCampaign(ProducerPurchaseImportRowDto raw,
                                                  List<CampaignEntity> all) {
        String label = blankToNull(raw.campaignLabel());
        if (label != null) {
            String canonical = FuzzyLabels.canonical(label);
            for (CampaignEntity c : all) {
                if (FuzzyLabels.canonical(c.label).equals(canonical)) return c;
            }
            for (CampaignEntity c : all) {
                if (FuzzyLabels.matches(c.label, label)) return c;
            }
            return null;
        }
        UUID id = parseUuid(raw.campaignId());
        if (id == null) return null;
        return all.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /** Vrai si les deux noms désignent vraisemblablement deux personnes. */
    private static boolean nameDiverges(String fromFile, String known) {
        String a = blankToNull(fromFile);
        if (a == null || known == null) return false;
        return !FuzzyLabels.matches(a, known);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
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
