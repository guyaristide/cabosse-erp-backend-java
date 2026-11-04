package com.ntech.cabosse.commodity.service;

import com.ntech.cabosse.shared.storage.AttachmentService;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.commodity.dto.CommoditySaleResponseDto;
import com.ntech.cabosse.commodity.dto.CommoditySaleUpsertDto;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.entity.SalesContractEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleRepository;
import com.ntech.cabosse.commodity.repository.SalesContractRepository;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.campaign.service.CampaignService;
import com.ntech.cabosse.commodity.dto.CommodityRefactionDashboardDto;
import com.ntech.cabosse.commodity.dto.CommodityRefactionDashboardDto.GradeQuantityLine;
import com.ntech.cabosse.commodity.dto.CommodityRefactionDashboardDto.NamedQuality;
import com.ntech.cabosse.commodity.dto.CommodityRefactionDashboardDto.RefactionCostLine;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.producerpurchase.repository.ProducerPurchaseRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.service.StockService;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Vente de cacao en gros / export (backlog NEG-02). Résout produit→article,
 * prix (contrat/campagne + marge) et primes, sort le stock au CMUP sur le
 * poids départ et poste l'écriture (411/701 + TVA). Marge dérivée du CMUP.
 */
@ApplicationScoped
public class CommoditySaleService {

    @Inject CommoditySaleRepository repo;
    @Inject com.ntech.cabosse.qualitygrade.service.QualityNormService qualityNorms;
    @Inject com.ntech.cabosse.qualitygrade.service.QualityGradeService qualityGrades;
    @Inject SalesContractRepository contracts;
    @Inject com.ntech.cabosse.dispatch.repository.DispatchNoteRepository dispatchNotes;
    @Inject CommodityRefService refService;
    @Inject CustomerRepository customers;
    @Inject ArticleRepository articles;
    @Inject CampaignService campaigns;
    // Résolution sans contrôle de capacité : les états de négoce restent
    // consultables par une structure sans module membres.
    @Inject CampaignResolver campaignResolver;
    @Inject StockService stockService;
    @Inject StockItemRepository stockItems;
    @Inject ProducerPurchaseRepository producerPurchases;
    @Inject AccountingService accounting;
    @Inject TenantPreferencesLookup preferences;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject AttachmentService attachments;

    public Pagination<CommoditySaleResponseDto> page(String q, UUID campaignId, UUID customerId, PageRequest pr) {
        long total = repo.countSearch(q, campaignId, customerId);
        var sort = com.ntech.cabosse.commodity.repository.CommoditySaleRepository.SORTABLE.resolve(pr);
        List<CommoditySaleResponseDto> items = repo.search(q, campaignId, customerId, pr.skip(), pr.perPage(), sort)
                .stream().map(CommoditySaleResponseDto::from).toList();
        return Pagination.of(total, pr, sort.names(), sort.order(), new java.util.HashMap<>(), items);
    }

    public CommoditySaleResponseDto getById(UUID id) {
        return CommoditySaleResponseDto.from(loadOrFail(id));
    }

    /** État de suivi des pertes / qualité pour une campagne (backlog NEG-02). */
    /**
     * La commission de collecte due sur ce décompte.
     *
     * <p>Un montant par kilo facturé, convenu avec le client et pour une
     * campagne donnée. Hors mandat, ou sans taux convenu pour la
     * campagne, il n'y a pas de commission : la structure vend pour son
     * compte et son produit est le prix de vente.</p>
     */
    private BigDecimal commissionFor(CommoditySaleEntity e, BigDecimal acceptedKg) {
        if (e.customerId == null || e.campaignId == null || acceptedKg == null) {
            return BigDecimal.ZERO;
        }
        var customer = customers.findById(e.customerId).orElse(null);
        if (customer == null || customer.commissionByCampaign == null) return BigDecimal.ZERO;
        return customer.commissionByCampaign.stream()
                .filter(m -> m != null && e.campaignId.equals(m.campaignId) && m.rate != null)
                .findFirst()
                .map(m -> m.rate.multiply(acceptedKg))
                .orElse(BigDecimal.ZERO);
    }

    public com.ntech.cabosse.commodity.dto.CommodityLossReportDto lossReport(UUID campaignId) {
        CampaignEntity campaign = campaignResolver.resolveOptional(campaignId);
        List<CommoditySaleEntity> sales = repo.listAll(campaign != null ? campaign.id : null);
        BigDecimal declared = BigDecimal.ZERO, discharged = BigDecimal.ZERO, accepted = BigDecimal.ZERO;
        BigDecimal commercial = BigDecimal.ZERO, ttc = BigDecimal.ZERO, margin = BigDecimal.ZERO;
        BigDecimal humSum = BigDecimal.ZERO, grainSum = BigDecimal.ZERO;
        int humCount = 0, grainCount = 0;
        for (CommoditySaleEntity s : sales) {
            declared = declared.add(nz(s.weights.declaredKg));
            discharged = discharged.add(nz(s.weights.dischargedKg));
            accepted = accepted.add(nz(s.weights.acceptedKg));
            commercial = commercial.add(nz(s.commercial));
            ttc = ttc.add(nz(s.amountInvoicedTtc));
            margin = margin.add(nz(s.margin));
            if (s.quality.humidityPct != null) { humSum = humSum.add(s.quality.humidityPct); humCount++; }
            if (s.quality.grainage != null) { grainSum = grainSum.add(s.quality.grainage); grainCount++; }
        }
        BigDecimal loss = declared.subtract(accepted);
        BigDecimal rate = declared.signum() > 0
                ? loss.multiply(new BigDecimal("100")).divide(declared, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgHum = humCount > 0
                ? humSum.divide(BigDecimal.valueOf(humCount), 2, RoundingMode.HALF_UP) : null;
        BigDecimal avgGrain = grainCount > 0
                ? grainSum.divide(BigDecimal.valueOf(grainCount), 2, RoundingMode.HALF_UP) : null;
        return new com.ntech.cabosse.commodity.dto.CommodityLossReportDto(
                campaign != null ? campaign.id : null,
                campaign != null ? campaign.label : null,
                sales.size(), declared, discharged, accepted, loss, rate,
                avgHum, avgGrain, commercial, ttc, margin);
    }

    /**
     * Tableau de bord des réfactions usines (backlog NEG-03) : perte valorisée
     * par type de réfaction (kg × prix bord champ de la campagne), taux sur le
     * volume déchargé, quantités et qualité moyennes par grade et par label.
     */
    public CommodityRefactionDashboardDto refactionDashboard(UUID campaignId) {
        CampaignEntity campaign = campaignResolver.resolveOptional(campaignId);
        List<CommoditySaleEntity> sales = repo.listAll(campaign != null ? campaign.id : null);

        // Prix bord champ par campagne, pour valoriser les réfactions au prix
        // d'achat au producteur (l'argent déjà payé et refusé par le client).
        java.util.Map<UUID, BigDecimal> basePrice = new java.util.HashMap<>();
        for (CampaignEntity c : campaigns.list()) basePrice.put(c.id, nz(c.basePricePerKg));

        // Les codes sont structurels : ils désignent les colonnes de
        // réfaction du bordereau usine. Les libellés, eux, viennent du
        // référentiel des seuils de qualité quand la structure les a
        // nommés ; sinon on retombe sur le code, plutôt que d'imposer le
        // vocabulaire d'une filière.
        String[] types = {"humidity", "foreignMatter", "moldy", "crabots", "broken", "waste", "other"};
        java.util.Map<String, String> normLabels = qualityNorms.list().stream()
                .collect(java.util.stream.Collectors.toMap(
                        n -> n.elementCode(), n -> n.label(), (a, b) -> a));
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = normLabels.getOrDefault(types[i], types[i]);
        }
        BigDecimal[] typeKg = new BigDecimal[7];
        BigDecimal[] typeCost = new BigDecimal[7];
        java.util.Arrays.fill(typeKg, BigDecimal.ZERO);
        java.util.Arrays.fill(typeCost, BigDecimal.ZERO);

        BigDecimal discharged = BigDecimal.ZERO, accepted = BigDecimal.ZERO;
        BigDecimal totalRefKg = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;

        QAcc overall = new QAcc();
        java.util.Map<String, QAcc> byGrade = new java.util.LinkedHashMap<>();
        java.util.Map<String, QAcc> byLabel = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> gradeQty = new java.util.LinkedHashMap<>();

        for (CommoditySaleEntity s : sales) {
            BigDecimal price = s.campaignId != null
                    ? basePrice.getOrDefault(s.campaignId, BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal[] kgs = {
                    nz(s.refactions.humidityKg), nz(s.refactions.foreignMatterKg), nz(s.refactions.moldyKg),
                    nz(s.refactions.crabotsKg), nz(s.refactions.brokenKg), nz(s.refactions.wasteKg),
                    nz(s.refactions.otherKg)
            };
            for (int i = 0; i < 7; i++) {
                typeKg[i] = typeKg[i].add(kgs[i]);
                BigDecimal cost = kgs[i].multiply(price);
                typeCost[i] = typeCost[i].add(cost);
                totalRefKg = totalRefKg.add(kgs[i]);
                totalCost = totalCost.add(cost);
            }
            discharged = discharged.add(nz(s.weights.dischargedKg));
            accepted = accepted.add(nz(s.weights.acceptedKg));

            overall.add(s.quality);
            String grade = normGrade(s.quality.grade);
            if (grade != null) {
                byGrade.computeIfAbsent(grade, k -> new QAcc()).add(s.quality);
                gradeQty.merge(grade, nz(s.weights.acceptedKg), BigDecimal::add);
            }
            String label = blankToNull(s.logistics.label);
            if (label != null) byLabel.computeIfAbsent(label, k -> new QAcc()).add(s.quality);
        }

        List<RefactionCostLine> costByType = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            costByType.add(new RefactionCostLine(types[i], labels[i], typeKg[i], typeCost[i]));
        }

        BigDecimal hundred = new BigDecimal("100");
        BigDecimal rate = discharged.signum() > 0
                ? totalRefKg.multiply(hundred).divide(discharged, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal totalGradeQty = gradeQty.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GradeQuantityLine> quantityByGrade = new java.util.ArrayList<>();
        for (var en : gradeQty.entrySet()) {
            BigDecimal pct = totalGradeQty.signum() > 0
                    ? en.getValue().multiply(hundred).divide(totalGradeQty, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            quantityByGrade.add(new GradeQuantityLine(en.getKey(), en.getValue(), pct));
        }

        BigDecimal volumePaid = producerPurchases.listAll(campaign != null ? campaign.id : null).stream()
                .map(pp -> nz(pp.weightKg)).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<NamedQuality> qByGrade = byGrade.entrySet().stream()
                .map(en -> new NamedQuality(en.getKey(), en.getValue().n, en.getValue().toDto())).toList();
        List<NamedQuality> qByLabel = byLabel.entrySet().stream()
                .map(en -> new NamedQuality(en.getKey(), en.getValue().n, en.getValue().toDto())).toList();

        return new CommodityRefactionDashboardDto(
                campaign != null ? campaign.id : null,
                campaign != null ? campaign.label : null,
                sales.size(), costByType, totalRefKg, totalCost,
                volumePaid, discharged, accepted, rate,
                quantityByGrade, overall.toDto(), qByGrade, qByLabel);
    }

    /** Les bordereaux appelés, dans l'ordre et sans doublon : la liste, puis l'ancien champ unique. */
    private List<com.ntech.cabosse.dispatch.entity.DispatchNoteEntity> loadNotes(CommoditySaleUpsertDto p) {
        java.util.LinkedHashSet<UUID> ids = new java.util.LinkedHashSet<>();
        if (p.dispatchNoteIds() != null) p.dispatchNoteIds().stream().filter(java.util.Objects::nonNull).forEach(ids::add);
        if (p.dispatchNoteId() != null) ids.add(p.dispatchNoteId());
        List<com.ntech.cabosse.dispatch.entity.DispatchNoteEntity> notes = new ArrayList<>();
        for (UUID id : ids) {
            notes.add(dispatchNotes.findById(id).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.dsp-note-not-found", id))));
        }
        return notes;
    }

    /**
     * Chaque chargement appelé, avec son site, et ce que le carnet en dit
     * quand l'import le porte ligne à ligne : sinon le numéro du bordereau
     * tient lieu de numéro de carnet.
     */
    private static List<CommoditySaleEntity.Load> loadsOf(
            List<com.ntech.cabosse.dispatch.entity.DispatchNoteEntity> notes, CommoditySaleUpsertDto p) {
        java.util.Map<UUID, CommoditySaleUpsertDto.LoadDto> given = new java.util.HashMap<>();
        if (p.loads() != null) {
            for (var l : p.loads()) if (l != null && l.dispatchNoteId() != null) given.put(l.dispatchNoteId(), l);
        }
        List<CommoditySaleEntity.Load> out = new ArrayList<>();
        for (var note : notes) {
            CommoditySaleEntity.Load load = new CommoditySaleEntity.Load();
            load.dispatchNoteId = note.id;
            load.dispatchNoteRef = note.ref;
            load.siteId = note.siteId;
            load.siteName = note.siteName;
            load.declaredKg = note.totalNetKg;
            load.bags = note.totalBags;
            var g = given.get(note.id);
            load.dispatchNoteNumber = g != null && g.dispatchNoteNumber() != null ? g.dispatchNoteNumber()
                    : (p.logistics() != null && notes.size() == 1 ? p.logistics().dispatchNoteNumber() : note.ref);
            load.loadingNumber = g != null ? g.loadingNumber()
                    : (p.logistics() != null && notes.size() == 1 ? p.logistics().loadingNumber() : null);
            load.departureLocation = g != null && g.departureLocation() != null ? g.departureLocation()
                    : (p.logistics() != null && notes.size() == 1 ? p.logistics().departureLocation() : note.siteName);
            out.add(load);
        }
        return out;
    }

    /** Ce qu'une vente coûte et rapporte, calculé de la saisie avant toute écriture. */
    private record Computed(
            com.ntech.cabosse.customer.entity.CustomerEntity customer,
            ArticleEntity article,
            CampaignEntity campaign,
            SalesContractEntity contract,
            List<com.ntech.cabosse.dispatch.entity.DispatchNoteEntity> notes,
            BigDecimal declaredKg, BigDecimal acceptedKg,
            BigDecimal price, BigDecimal commercial,
            BigDecimal coopPrime, BigDecimal producerPrime, BigDecimal socialPrime, BigDecimal totalPrime,
            BigDecimal ht, BigDecimal vatRate, BigDecimal vat, BigDecimal ttc,
            BigDecimal cmup, BigDecimal cogs, BigDecimal margin
    ) {
        com.ntech.cabosse.dispatch.entity.DispatchNoteEntity dispatchNote() {
            return notes.isEmpty() ? null : notes.get(0);
        }
    }

    /**
     * Lit la saisie et calcule la vente, sans rien écrire. Les bordereaux
     * doivent être ouverts, sauf ceux que {@code ownSaleId} a déjà
     * vendus : une vente qui se modifie garde les siens.
     */
    private Computed compute(CommoditySaleUpsertDto p, UUID ownSaleId) {
        TenantPreferences prefs = preferences.current();

        var customer = customers.findById(p.customerId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cco-customer-not-found", p.customerId())));

        ArticleEntity article = articles.findById(p.articleId()).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cco-article-not-found", p.articleId())));
        if (!article.sellable) {
            throw new BusinessException(Messages.msg("m.cco-article-not-sellable", article.name));
        }

        // Rattachement par la date de vente, comme partout ailleurs : ce
        // service résolvait la campagne du jour de saisie alors que la date
        // de l'opération était juste sous la main.
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(p.date(), p.campaignId());

        SalesContractEntity contract = p.contractId() != null
                ? contracts.findById(p.contractId()).orElseThrow(
                        () -> new NotFoundException(Messages.msg("m.cco-contract-not-found", p.contractId())))
                : null;

        // La zone A de l'expert : la vente appelle un bordereau de sortie,
        // dont le poids déclaré et la sortie de stock font foi. Sans
        // bordereau (reprise, vente directe), l'ancien chemin demeure.
        // Un ou plusieurs bordereaux : le cacao vendu se constitue à
        // partir de plusieurs magasins de la coopérative (demandé le
        // 21/10/2026, VTE-16). Chacun apporte son poids et son coût ; le
        // site de la vente est celui qui facture, pas celui d'où tout
        // part, et le contrôle de site est tombé avec cette lecture.
        List<com.ntech.cabosse.dispatch.entity.DispatchNoteEntity> notes = loadNotes(p);
        for (var note : notes) {
            boolean ownSold = ownSaleId != null
                    && note.status == com.ntech.cabosse.dispatch.entity.DispatchNoteStatus.SOLD
                    && ownSaleId.equals(note.saleId);
            if (note.status != com.ntech.cabosse.dispatch.entity.DispatchNoteStatus.OPEN && !ownSold) {
                throw new BusinessException(Messages.msg("m.cco-dispatch-not-open",
                        note.ref, note.saleRef != null ? note.saleRef : ""));
            }
            if (!note.articleId.equals(p.articleId())) {
                throw new BusinessException(Messages.msg("m.cco-dispatch-other-article",
                        note.ref, note.articleName));
            }
        }
        com.ntech.cabosse.dispatch.entity.DispatchNoteEntity dispatchNote = notes.isEmpty() ? null : notes.get(0);
        BigDecimal declaredKg = dispatchNote != null
                ? notes.stream().map(n -> nz(n.totalNetKg)).reduce(BigDecimal.ZERO, BigDecimal::add)
                : required(p.weights() != null ? p.weights().declaredKg() : null, "Poids déclaré (départ)");
        BigDecimal acceptedKg = required(p.weights() != null ? p.weights().acceptedKg() : null, "Poids accepté");

        BigDecimal price = p.pricePerKg() != null ? p.pricePerKg()
                : nz(campaign != null ? campaign.basePricePerKg : null)
                        .add(nz(contract != null ? contract.marginPerKg : null));
        if (price.signum() <= 0) throw new BusinessException(Messages.msg("m.cco-price-required"));

        BigDecimal commercial = acceptedKg.multiply(price);
        BigDecimal coopPrime = p.coopPrime() != null ? p.coopPrime()
                : nz(contract != null ? contract.coopPrimePerKg : null).multiply(acceptedKg);
        BigDecimal producerPrime = p.producerPrime() != null ? p.producerPrime()
                : nz(contract != null ? contract.producerPrimePerKg : null).multiply(acceptedKg);
        BigDecimal socialPrime = p.socialPrime() != null ? p.socialPrime()
                : nz(contract != null ? contract.socialPrimePerKg : null).multiply(acceptedKg);
        BigDecimal totalPrime = coopPrime.add(producerPrime).add(socialPrime);

        BigDecimal ht = commercial.add(totalPrime);
        BigDecimal vatRate = p.vatRatePct() != null ? p.vatRatePct() : prefs.commoditySaleVatRatePct();
        BigDecimal vat = ht.multiply(nz(vatRate)).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal ttc = ht.add(vat);

        BigDecimal cmup;
        BigDecimal cogs;
        if (dispatchNote != null) {
            // Le coût des ventes est celui des lignes réellement chargées,
            // au CMUP photographié à leur sortie : l'exact, pas l'actuel,
            // chaque site apportant le sien.
            BigDecimal value = BigDecimal.ZERO;
            for (var note : notes) {
                for (com.ntech.cabosse.dispatch.entity.DispatchLine line : note.lines) {
                    value = value.add(nz(line.netKg).multiply(nz(line.cmupAtDispatch)));
                }
            }
            cogs = value;
            cmup = declaredKg.signum() > 0
                    ? value.divide(declaredKg, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } else {
            cmup = stockItems.findByArticleAndSite(article.id, p.siteId())
                    .map(it -> it.cmup).orElse(BigDecimal.ZERO);
            cogs = declaredKg.multiply(nz(cmup));
        }
        BigDecimal margin = ht.subtract(cogs);
        return new Computed(customer, article, campaign, contract, notes, declaredKg, acceptedKg,
                price, commercial, coopPrime, producerPrime, socialPrime, totalPrime,
                ht, vatRate, vat, ttc, cmup, cogs, margin);
    }

    /** Pose la saisie et le calcul sur l'entité, création ou modification. */
    private void fill(CommoditySaleEntity e, CommoditySaleUpsertDto p, Computed c) {
        var campaign = c.campaign();
        var contract = c.contract();
        var article = c.article();
        var notes = c.notes();
        var dispatchNote = c.dispatchNote();
        e.date = p.date();
        e.campaignId = campaign != null ? campaign.id : null;
        e.campaignYear = campaign != null ? campaign.campaignYear : null;
        e.campaignKind = campaign != null ? campaign.kind : null;
        e.customerId = c.customer().id;
        e.customerName = c.customer().name;
        e.contractId = contract != null ? contract.id : null;
        e.articleId = article.id;
        e.articleCode = article.code;
        e.articleName = article.name;
        e.articleUnit = article.unit;
        e.siteId = p.siteId();
        if (dispatchNote != null) {
            e.dispatchNoteId = dispatchNote.id;
            e.dispatchNoteRef = dispatchNote.ref;
            e.dispatchNoteIds = notes.stream().map(n -> n.id).toList();
            e.dispatchNoteRefs = notes.stream().map(n -> n.ref).toList();
            e.loads = loadsOf(notes, p);
        } else {
            e.dispatchNoteId = null;
            e.dispatchNoteRef = null;
            e.dispatchNoteIds = List.of();
            e.dispatchNoteRefs = List.of();
            e.loads = List.of();
        }
        e.logistics = new CommoditySaleEntity.Logistics();
        e.weights = new CommoditySaleEntity.Weights();
        e.refactions = new CommoditySaleEntity.Refactions();
        e.quality = new CommoditySaleEntity.Quality();
        e.settlement = new CommoditySaleEntity.Settlement();
        mapLogistics(e, p);
        mapWeights(e, p);
        if (dispatchNote != null) {
            e.weights.declaredKg = c.declaredKg();
        }
        mapRefactions(e, p);
        mapQuality(e, p);
        e.pricePerKg = c.price();
        e.commercial = c.commercial();
        e.coopPrime = c.coopPrime();
        e.producerPrime = c.producerPrime();
        e.socialPrime = c.socialPrime();
        e.totalPrime = c.totalPrime();
        e.amountInvoicedHt = c.ht();
        e.vatRatePct = nz(c.vatRate());
        e.vat = c.vat();
        e.amountInvoicedTtc = c.ttc();
        e.cmupAtSale = nz(c.cmup());
        e.cogs = c.cogs();
        e.margin = c.margin();
        // Le décompte se calcule du facturé : il le suit.
        mapSettlement(e, p);
    }

    /**
     * La sortie de stock de la vente. Avec un bordereau de sortie, elle
     * est déjà faite au chargement, ligne à ligne sous les lots des reçus :
     * la vente le marque vendu, atomiquement, une seule vente par
     * chargement. Sans bordereau, l'ancien chemin sort le poids déclaré
     * au CMUP.
     */
    private void takeStock(CommoditySaleEntity e, CommoditySaleUpsertDto p, Computed c) {
        if (c.dispatchNote() != null) {
            // Tous vendus, ou aucun : un bordereau pris entre-temps par une
            // autre vente rend ceux déjà marqués.
            List<UUID> marked = new ArrayList<>();
            for (var note : c.notes()) {
                if (!dispatchNotes.tryMarkSold(note.id, e.id, e.ref)) {
                    marked.forEach(dispatchNotes::unmarkSold);
                    throw new BusinessException(Messages.msg("m.cco-dispatch-not-open", note.ref, ""));
                }
                marked.add(note.id);
            }
            e.movementRef = String.join(" + ", e.dispatchNoteRefs);
        } else {
            // Les sacs partent avec le poids : le registre du magasin les
            // compte, et une sortie sans sacs y laissait un compte qui ne
            // baissait jamais (expert, 16/10/2026).
            stockService.applyMovement(new MovementInput(
                    c.article().id, p.siteId(), MovementKind.OUT, c.declaredKg(), nz(c.cmup()),
                    MovementSource.COMMODITY_SALE, e.ref, e.id, null,
                    "Vente " + c.article().name + " " + e.customerName, null,
                    p.date().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    false, null, false, e.weights == null ? null : e.weights.sacsAccepted));
            e.movementRef = e.ref;
        }
    }

    /** Défait la sortie de stock telle que l'entité la porte : bordereaux rendus ou poids rentré. */
    private void giveStockBack(CommoditySaleEntity e, String reason) {
        if (e.dispatchNoteIds != null && !e.dispatchNoteIds.isEmpty()) {
            e.dispatchNoteIds.forEach(dispatchNotes::unmarkSold);
        } else if (e.articleId != null && e.siteId != null && e.weights != null
                && e.weights.declaredKg != null && e.weights.declaredKg.signum() > 0) {
            stockService.applyMovement(new MovementInput(
                    e.articleId, e.siteId, MovementKind.IN, e.weights.declaredKg, nz(e.cmupAtSale),
                    MovementSource.COMMODITY_SALE, e.ref, e.id, null,
                    reason + " " + e.ref, null,
                    e.date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), true, null, false));
        }
    }

    /** La source de la pièce comptable en cours : la vente elle-même, puis chaque version. */
    private static UUID pieceSourceOf(CommoditySaleEntity e) {
        return e.pieceSourceId != null ? e.pieceSourceId : e.id;
    }

    public CommoditySaleResponseDto create(CommoditySaleUpsertDto p) {
        Computed c = compute(p, null);
        Instant now = Instant.now();
        CommoditySaleEntity e = new CommoditySaleEntity();
        e.id = idGenerator.newId();
        e.ref = refService.nextSale();
        e.pieceSourceId = e.id;
        fill(e, p, c);
        e.createdAt = now;
        e.updatedAt = now;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();

        // 1) Sortie de stock.
        takeStock(e, p, c);

        // 2) Écriture 411/701 (+ TVA). Si elle échoue (période close), on
        //    défait ce que l'étape 1 a fait.
        try {
            accounting.postFromCommoditySale(e.pieceSourceId, e.ref, e.customerId, e.customerName,
                            c.article().salesRevenueAccount,
                            c.ht(), c.vat(), p.date(), commissionFor(e, c.acceptedKg()))
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        } catch (RuntimeException ex) {
            giveStockBack(e, "Annulation vente");
            throw ex;
        }

        repo.insert(e);

        audit.event(AuditEventType.COMMODITY_SALE_CREATED)
                .actorEmail(actor())
                .target("commodity_sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Vente négoce " + e.ref + " : " + e.customerName + " · " + c.acceptedKg()
                        + " kg acceptés (" + c.ttc() + ")")
                .record();

        return CommoditySaleResponseDto.from(e);
    }

    /**
     * Modifie une vente saisie (VTE-25, demandé le 04/11/2026) : la vente
     * garde sa référence et ses encaissements, tout le reste se ressaisit
     * et se recalcule. Ce qui avait été écrit se défait d'abord : la pièce
     * comptable est contre-passée, les bordereaux rendus ou le poids
     * rentré ; puis la nouvelle version repart comme une création, avec
     * une pièce à elle. Rien ne se corrige en place dans le journal.
     */
    public CommoditySaleResponseDto update(UUID id, CommoditySaleUpsertDto p) {
        CommoditySaleEntity e = loadOrFail(id);
        CommoditySaleEntity previous = loadOrFail(id);
        Computed c = compute(p, e.id);
        UUID previousSource = pieceSourceOf(previous);

        // 1) Le stock : rendre ce que l'ancienne version avait pris, prendre
        //    ce que la nouvelle demande. Si la nouvelle ne peut pas (un
        //    bordereau pris entre-temps), l'ancienne reprend le sien et
        //    rien d'autre n'a bougé.
        giveStockBack(previous, "Modification vente");
        fill(e, p, c);
        e.pieceSourceId = idGenerator.newId();
        e.updatedAt = Instant.now();
        try {
            takeStock(e, p, c);
        } catch (RuntimeException ex) {
            restoreStock(previous);
            throw ex;
        }

        // 2) La pièce de la nouvelle version, sous sa propre source. Si la
        //    période refuse, tout revient : la pièce d'origine est intacte.
        try {
            accounting.postFromCommoditySale(e.pieceSourceId, e.ref, e.customerId, e.customerName,
                            c.article().salesRevenueAccount,
                            c.ht(), c.vat(), p.date(), commissionFor(e, c.acceptedKg()))
                    .ifPresent(piece -> e.pieceRef = piece.ref);
        } catch (RuntimeException ex) {
            giveStockBack(e, "Annulation modification");
            restoreStock(previous);
            throw ex;
        }

        // 3) La contre-passation de l'ancienne pièce, en dernier : si elle
        //    échoue, la nouvelle pièce est contre-passée à son tour et la
        //    vente reste ce qu'elle était.
        try {
            accounting.reverseFrom(com.ntech.cabosse.accounting.entity.PostingSourceType.COMMODITY_SALE,
                    previousSource, "modification de " + e.ref);
        } catch (RuntimeException ex) {
            accounting.reverseFrom(com.ntech.cabosse.accounting.entity.PostingSourceType.COMMODITY_SALE,
                    e.pieceSourceId, "modification abandonnée de " + e.ref);
            giveStockBack(e, "Annulation modification");
            restoreStock(previous);
            throw ex;
        }

        repo.replace(e);

        audit.event(AuditEventType.COMMODITY_SALE_UPDATED)
                .actorEmail(actor())
                .target("commodity_sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Vente négoce " + e.ref + " modifiée : " + e.customerName + " · " + c.acceptedKg()
                        + " kg acceptés (" + c.ttc() + ")")
                .record();

        return CommoditySaleResponseDto.from(e);
    }

    /** Remet la sortie de stock d'une version telle qu'elle était, après l'avoir rendue. */
    private void restoreStock(CommoditySaleEntity e) {
        if (e.dispatchNoteIds != null && !e.dispatchNoteIds.isEmpty()) {
            e.dispatchNoteIds.forEach(noteId -> dispatchNotes.tryMarkSold(noteId, e.id, e.ref));
        } else if (e.articleId != null && e.siteId != null && e.weights != null
                && e.weights.declaredKg != null && e.weights.declaredKg.signum() > 0) {
            stockService.applyMovement(new MovementInput(
                    e.articleId, e.siteId, MovementKind.OUT, e.weights.declaredKg, nz(e.cmupAtSale),
                    MovementSource.COMMODITY_SALE, e.ref, e.id, null,
                    "Vente " + e.articleName + " " + e.customerName, null,
                    e.date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    false, null, false, e.weights.sacsAccepted));
        }
    }

    // ─── Mapping des blocs ──────────────────────────────────────────

    private static void mapLogistics(CommoditySaleEntity e, CommoditySaleUpsertDto p) {
        if (p.logistics() == null) return;
        var l = p.logistics();
        e.logistics.dispatchNoteNumber = blankToNull(l.dispatchNoteNumber());
        e.logistics.loadingNumber = blankToNull(l.loadingNumber());
        e.logistics.departureLocation = blankToNull(l.departureLocation());
        e.logistics.destination = blankToNull(l.destination());
        e.logistics.connaissementRef = blankToNull(l.connaissementRef());
        e.logistics.label = blankToNull(l.label());
        e.logistics.originSections = blankToNull(l.originSections());
    }

    private static void mapWeights(CommoditySaleEntity e, CommoditySaleUpsertDto p) {
        var w = p.weights();
        e.weights.declaredKg = w.declaredKg();
        e.weights.dischargedKg = w.dischargedKg();
        e.weights.acceptedKg = w.acceptedKg();
        e.weights.sacsAccepted = w.sacsAccepted();
        e.weights.sacsMissing = w.sacsMissing();
        e.weights.sacsRejected = w.sacsRejected();
    }

    private static void mapRefactions(CommoditySaleEntity e, CommoditySaleUpsertDto p) {
        if (p.refactions() == null) return;
        var r = p.refactions();
        e.refactions.usineKg = r.usineKg();
        e.refactions.humidityKg = r.humidityKg();
        e.refactions.foreignMatterKg = r.foreignMatterKg();
        e.refactions.moldyKg = r.moldyKg();
        e.refactions.crabotsKg = r.crabotsKg();
        e.refactions.brokenKg = r.brokenKg();
        e.refactions.wasteKg = r.wasteKg();
        e.refactions.otherKg = r.otherKg();
    }

    /**
     * Le décompte de l'acheteur, selon la règle donnée le 04/11/2026
     * (VTE-26) : valeur totale = facturé TTC + péréquation transport +
     * différentiel ramassage ; total des retenues = BIC + timbre fiscal +
     * commission commerciale + remboursement mandat + remboursement
     * revolving + mise en compte ; montant net = valeur totale − retenues.
     *
     * <p>Les composantes se saisissent, les trois totaux se calculent :
     * ce que le fichier ou le formulaire en disait est ignoré. Aucune
     * composante renseignée, aucun décompte.</p>
     */
    private void mapSettlement(CommoditySaleEntity e, CommoditySaleUpsertDto p) {
        if (p.settlement() == null) return;
        var d = p.settlement();
        e.settlement.transportEqualization = d.transportEqualization();
        e.settlement.gatheringDifferential = d.gatheringDifferential();
        e.settlement.bic = d.bic();
        e.settlement.fiscalStamp = d.fiscalStamp();
        e.settlement.salesCommission = d.salesCommission();
        e.settlement.mandateRepayment = d.mandateRepayment();
        e.settlement.revolvingRepayment = d.revolvingRepayment();
        e.settlement.collectorRetention = d.collectorRetention();
        computeSettlementTotals(e.settlement, e.amountInvoicedTtc);
    }

    /** Les trois totaux du décompte, depuis ses composantes et le facturé TTC. */
    public static void computeSettlementTotals(CommoditySaleEntity.Settlement s, BigDecimal invoicedTtc) {
        if (s == null) return;
        boolean any = s.transportEqualization != null || s.gatheringDifferential != null
                || s.bic != null || s.fiscalStamp != null || s.salesCommission != null
                || s.mandateRepayment != null || s.revolvingRepayment != null || s.collectorRetention != null;
        if (!any) {
            s.totalValue = null;
            s.totalDeductions = null;
            s.netAmount = null;
            return;
        }
        s.totalValue = nz(invoicedTtc).add(nz(s.transportEqualization)).add(nz(s.gatheringDifferential));
        s.totalDeductions = nz(s.bic).add(nz(s.fiscalStamp)).add(nz(s.salesCommission))
                .add(nz(s.mandateRepayment)).add(nz(s.revolvingRepayment)).add(nz(s.collectorRetention));
        s.netAmount = s.totalValue.subtract(s.totalDeductions);
    }

    private void mapQuality(CommoditySaleEntity e, CommoditySaleUpsertDto p) {
        if (p.quality() == null) return;
        var q = p.quality();
        e.quality.grainage = q.grainage();
        e.quality.moldyPct = q.moldyPct();
        e.quality.slatePct = q.slatePct();
        e.quality.wastePct = q.wastePct();
        e.quality.crabotsPct = q.crabotsPct();
        e.quality.fatContent = q.fatContent();
        e.quality.fineness = q.fineness();
        e.quality.purplePct = q.purplePct();
        e.quality.mitedPct = q.mitedPct();
        e.quality.flatPct = q.flatPct();
        e.quality.germinatedPct = q.germinatedPct();
        e.quality.defectivePct = q.defectivePct();
        e.quality.foreignMatterPct = q.foreignMatterPct();
        e.quality.ffaPct = q.ffaPct();
        e.quality.brokenPct = q.brokenPct();
        e.quality.humidityPct = q.humidityPct();
        e.quality.taste = blankToNull(q.taste());
        // Le grade est vérifié contre la grille du tenant, comme au
        // contrôle qualité : deux nomenclatures pour une seule notion
        // rendaient les états par grade incomparables.
        e.quality.grade = qualityGrades.requireCode(q.grade());
        e.quality.analysisResult = blankToNull(q.analysisResult());
    }

    private static BigDecimal required(BigDecimal v, String label) {
        if (v == null || v.signum() <= 0) throw new BusinessException(Messages.msg("m.cco-field-required-positive", label));
        return v;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String normGrade(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toUpperCase(java.util.Locale.ROOT);
    }

    /** Accumulateur de moyennes des 12 éléments de qualité pour un groupe. */
    private static final class QAcc {
        final BigDecimal[] sum = new BigDecimal[12];
        final int[] cnt = new int[12];
        int n = 0;

        QAcc() { java.util.Arrays.fill(sum, BigDecimal.ZERO); }

        void add(CommoditySaleEntity.Quality q) {
            n++;
            acc(0, q.grainage); acc(1, q.moldyPct); acc(2, q.slatePct); acc(3, q.purplePct);
            acc(4, q.mitedPct); acc(5, q.flatPct); acc(6, q.germinatedPct); acc(7, q.defectivePct);
            acc(8, q.foreignMatterPct); acc(9, q.ffaPct); acc(10, q.brokenPct); acc(11, q.humidityPct);
        }

        private void acc(int i, BigDecimal v) { if (v != null) { sum[i] = sum[i].add(v); cnt[i]++; } }

        private BigDecimal avg(int i) {
            return cnt[i] > 0 ? sum[i].divide(BigDecimal.valueOf(cnt[i]), 2, RoundingMode.HALF_UP) : null;
        }

        CommodityRefactionDashboardDto.QualityAverage toDto() {
            return new CommodityRefactionDashboardDto.QualityAverage(
                    avg(0), avg(1), avg(2), avg(3), avg(4), avg(5),
                    avg(6), avg(7), avg(8), avg(9), avg(10), avg(11));
        }
    }

    private CommoditySaleEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException(Messages.msg("m.cco-sale-not-found", id)));
    }

    /**
     * Constat d'un encaissement client (CE-194, page 3 du modèle) : le
     * comptable saisit la date, le montant et la référence du règlement,
     * l'écriture trésorerie / client part, et le solde de la vente suit.
     * Un règlement partiel est un fait, pas une erreur : le solde reste
     * au client. Encaisser plus que le solde est refusé, une créance ne
     * devenant pas une dette par un excès de zèle.
     */
    public com.ntech.cabosse.commodity.dto.CommoditySaleResponseDto recordPayment(
            UUID saleId, com.ntech.cabosse.commodity.dto.RecordSalePaymentDto p) {
        CommoditySaleEntity e = repo.findById(saleId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.cco-sale-not-found", saleId)));
        // Le solde part du net du décompte quand il y en a un (VTE-27).
        BigDecimal paid = nz(e.totalPaid);
        BigDecimal remaining = e.amountDue().subtract(paid);
        if (p.amount().compareTo(remaining) > 0) {
            throw new BusinessException(Messages.msg("m.cco-payment-exceeds-due",
                    p.amount(), remaining));
        }

        String treasuryAccount = accounting.treasuryAccountFor(p.method(), p.bankAccountId());
        com.ntech.cabosse.commodity.entity.CommoditySalePayment payment =
                new com.ntech.cabosse.commodity.entity.CommoditySalePayment();
        payment.id = idGenerator.newId();
        payment.paidOn = p.paidOn();
        payment.amount = p.amount();
        payment.method = p.method();
        payment.bankAccountId = p.bankAccountId();
        payment.paymentRef = p.paymentRef().trim();
        payment.notes = blankToNull(p.notes());
        payment.recordedByEmail = actor();
        payment.recordedAt = Instant.now();

        accounting.postFromCommoditySalePayment(payment.id, e.ref, e.customerId, e.customerName,
                        treasuryAccount, p.amount(), p.paidOn(), payment.paymentRef)
                .ifPresent(piece -> payment.pieceRef = piece.ref);

        if (e.payments == null) e.payments = new java.util.ArrayList<>();
        e.payments.add(payment);
        e.totalPaid = paid.add(p.amount());
        e.updatedAt = Instant.now();
        repo.replace(e);

        audit.event(AuditEventType.COMMODITY_SALE_CREATED)
                .actorEmail(actor())
                .target("commodity_sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Encaissement sur la vente " + e.ref + " : " + p.amount() + " "
                        + Messages.currencyLabel() + " (" + payment.paymentRef + ")")
                .record();
        return com.ntech.cabosse.commodity.dto.CommoditySaleResponseDto.from(e);
    }

    private String actor() { try { return jwt.getName(); } catch (Exception e) { return null; } }
    private UUID safeUserId() { try { return tenantContext.userId(); } catch (Exception e) { return null; } }
    // ─── Pièces jointes ─────────────────────────────────────────────

    /** Dépose une pièce justificative sur commodity sale (demandé le 22/10/2026). */
    public CommoditySaleResponseDto attach(UUID id, byte[] bytes, String mimeType, String fileName, String label) {
        CommoditySaleEntity e = loadOrFail(id);
        var ref = attachments.store(bytes, mimeType, fileName, label, e.id, "commodity_sale");
        repo.pushAttachment(e.id, ref);
        audit.event(AuditEventType.COMMODITY_SALE_ATTACHMENT)
                .actorEmail(actor())
                .target("commodity_sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » ajoutée sur " + e.ref)
                .record();
        return CommoditySaleResponseDto.from(loadOrFail(id));
    }

    /** Retire une pièce et archive son binaire. */
    public CommoditySaleResponseDto detach(UUID id, UUID fileId) {
        CommoditySaleEntity e = loadOrFail(id);
        var ref = attachments.find(e.attachments, fileId);
        repo.pullAttachment(e.id, fileId);
        attachments.discard(ref);
        audit.event(AuditEventType.COMMODITY_SALE_ATTACHMENT)
                .actorEmail(actor())
                .target("commodity_sale", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Pièce jointe « " + (ref.label != null ? ref.label : ref.fileName)
                        + " » retirée de " + e.ref)
                .record();
        return CommoditySaleResponseDto.from(loadOrFail(id));
    }

    /** Contenu d'une pièce, servi en téléchargement. */
    public com.ntech.cabosse.shared.storage.AttachmentService.AttachmentStream openAttachment(
            UUID id, UUID fileId) {
        return attachments.open(loadOrFail(id).attachments, fileId);
    }
}
