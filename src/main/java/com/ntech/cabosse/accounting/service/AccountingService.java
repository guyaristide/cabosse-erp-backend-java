package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.JournalPieceEntity;
import com.ntech.cabosse.accounting.entity.PostingSourceType;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.entity.PurchaseOrderLine;
import com.ntech.cabosse.article.entity.ArticleType;
import com.ntech.cabosse.reception.entity.DirectReceiptEntity;
import com.ntech.cabosse.reception.entity.DirectReceiptLine;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SaleLine;
import com.ntech.cabosse.sale.entity.SalePayment;
import com.ntech.cabosse.accounting.entity.QuarantineStatus;
import com.ntech.cabosse.accounting.entity.QuarantinedPostingEntity;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cœur du module comptabilité — point d'entrée unique pour générer une
 * pièce comptable depuis n'importe quelle source métier.
 *
 * <p>Architecture : appel <strong>synchrone</strong> depuis les services
 * métier (PurchaseOrderService, SaleService, DirectReceiptService). On ne
 * passe pas par l'event bus pour deux raisons :
 * <ol>
 *   <li>Cohérence : si la compta plante, on ne veut pas que la livraison
 *       stock soit déjà effective (le miroir Compta/Stock doit rester
 *       parfait). En sync, l'exception remonte et tout rollback.</li>
 *   <li>Simplicité : pas d'idempotence à gérer côté event delivery — on
 *       l'a déjà via l'index unique {@code (sourceType, sourceId)}.</li>
 * </ol>
 *
 * <p><strong>Règle TVA non récupérable</strong> : si
 * la résolution renvoie {@code false}, la TVA n'est pas portée sur 4456 ;
 * elle est intégrée au montant débité sur les comptes de charges, ligne
 * par ligne, au prorata HT. Le coefficient {@code (1 + vatRate/100)}
 * s'applique <em>après</em> la ventilation du transport (déjà gérée en
 * amont par {@code PurchaseOrderService.postStockEntries}). Ici on
 * comptabilise les flux financiers — le débit charge ligne par ligne
 * inclut donc déjà la quote-part TVA non déductible.</p>
 */
@ApplicationScoped
public class AccountingService {

    private static final Logger LOG = Logger.getLogger(AccountingService.class);
    private static final int MONEY_SCALE = 0; // FCFA = pas de décimales
    private static final int VAT_RATIO_SCALE = 6;

    @Inject JournalPieceRepository pieces;
    @Inject com.ntech.cabosse.accounting.repository.AccountingPeriodRepository periods;
    @Inject com.ntech.cabosse.accounting.repository.QuarantinedPostingRepository quarantined;
    @Inject JournalPieceRefService refService;
    @Inject AccountingPeriodService periodService;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.campaign.service.CampaignResolver campaignResolver;
    @Inject TenantContext tenantContext;
    @Inject com.ntech.cabosse.tenant.service.TenantPreferencesLookup preferencesLookup;
    @Inject com.ntech.cabosse.article.repository.ArticleRepository articles;
    @Inject com.ntech.cabosse.analytics.repository.CostCenterRepository costCenters;
    @Inject com.ntech.cabosse.analytics.repository.AllocationKeyRepository allocationKeys;

    // ════════════════════════════════════════════════════════════════
    //  Entrée unique : postPiece + reverseFrom
    // ════════════════════════════════════════════════════════════════

    /**
     * Persiste une pièce comptable. Vérifie l'équilibre débit/crédit et
     * l'idempotence sur {@code (sourceType, sourceId)}.
     *
     * <p>No-op silencieux si une pièce existe déjà pour ce couple — c'est
     * la sécurité contre un événement métier rejoué.</p>
     */
    /**
     * Applique le réglage du tenant quand la période d'effet est close.
     *
     * <p>Rend la demande à passer, éventuellement redatée, ou {@code null}
     * pour signifier « mise en quarantaine, ne rien écrire ». La période
     * ouverte reste le cas courant : la méthode ne fait rien si la date
     * d'effet est passable.</p>
     *
     * <p>Les trois issues partagent un invariant : la saisie n'est jamais
     * perdue. C'est exactement ce que le comportement précédent, un refus
     * sec, ne garantissait pas.</p>
     */
    private PostingRequest applyClosedPeriodPolicy(PostingRequest request, LocalDate effective) {
        if (!periods.isLocked(java.time.YearMonth.from(effective).toString())) {
            return request;
        }
        String policy = preferencesLookup.current().closedPeriodPolicy();

        if (TenantPreferences.CLOSED_PERIOD_REFUSE.equals(policy)) {
            periodService.assertOpen(effective);
            return request;
        }

        if (TenantPreferences.CLOSED_PERIOD_POST_TO_OPEN.equals(policy)) {
            LocalDate target = firstOpenDateOnOrAfter(effective);
            if (target == null) {
                // Aucune période ouverte trouvée : mieux vaut retenir que
                // deviner une date, la quarantaine reste le filet.
                quarantine(request, effective);
                return null;
            }
            // La date comptable s'écarte volontairement de la date
            // d'opération : la mention doit rester lisible sur la pièce.
            String note = request.libelle() + " (opération du "
                    + effective.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ")";
            return new PostingRequest(target, request.sourceType(), request.sourceId(),
                    request.sourceRef(), note, request.entries());
        }

        quarantine(request, effective);
        return null;
    }

    /** Première date passable à partir de {@code from}, dans les 24 mois. */
    private LocalDate firstOpenDateOnOrAfter(LocalDate from) {
        java.time.YearMonth month = java.time.YearMonth.from(from);
        for (int i = 0; i < 24; i++) {
            if (!periods.isLocked(month.toString())) {
                // Premier jour du mois ouvert, sauf si la date d'origine y
                // tombe déjà (cas d'un mois partiellement rouvert).
                LocalDate candidate = month.atDay(1);
                return candidate.isBefore(from) ? from : candidate;
            }
            month = month.plusMonths(1);
        }
        return null;
    }

    /** Retient l'écriture pour régularisation, sans rien écrire au journal. */
    private void quarantine(PostingRequest request, LocalDate effective) {
        String period = java.time.YearMonth.from(effective).toString();
        // Un rejeu de la même opération ne doit pas empiler les demandes.
        if (quarantined.findPendingBySource(request.sourceType().name(), request.sourceId()).isPresent()) {
            LOG.debugf("Écriture déjà en quarantaine pour %s/%s : no-op",
                    request.sourceType(), request.sourceId());
            return;
        }
        QuarantinedPostingEntity q = new QuarantinedPostingEntity();
        q.id = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch();
        q.sourceType = request.sourceType();
        q.sourceId = request.sourceId();
        q.sourceRef = request.sourceRef();
        q.date = effective;
        q.libelle = request.libelle();
        q.entries = new java.util.ArrayList<>(request.entries());
        q.totalDebitFcfa = sumSide(request.entries(), true);
        q.totalCreditFcfa = sumSide(request.entries(), false);
        q.lockedPeriod = period;
        q.status = QuarantineStatus.PENDING;
        q.createdAt = java.time.Instant.now();
        quarantined.insert(q);
        LOG.infof("Écriture %s retenue : période %s close", request.sourceRef(), period);
    }

    private static BigDecimal sumSide(List<JournalEntry> entries, boolean debit) {
        BigDecimal total = BigDecimal.ZERO;
        for (JournalEntry e : entries) {
            BigDecimal v = debit ? e.debitFcfa : e.creditFcfa;
            if (v != null) total = total.add(v);
        }
        return total;
    }

    public Optional<JournalPieceEntity> postPiece(PostingRequest request) {
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new BusinessException(Messages.msg("m.acc-piece-empty"));
        }

        // 1. Idempotence
        Optional<JournalPieceEntity> existing = pieces.findBySource(request.sourceType(), request.sourceId());
        if (existing.isPresent()) {
            LOG.debugf("Pièce déjà comptabilisée pour %s/%s : no-op",
                    request.sourceType(), request.sourceId());
            return existing;
        }

        // 2. Période ouverte — une période clôturée refuse toute pièce,
        //    quelle que soit la source (livraison, vente, régularisation…).
        // Les pièces de fin d'exercice (EXERCISE_*) sont datées du dernier
        // jour d'un exercice dont tous les mois sont verrouillés — c'est le
        // seul flux autorisé à écrire dans une période close (CPT-12).
        if (!request.sourceType().name().startsWith("EXERCISE_")) {
            LocalDate effective = request.date() != null ? request.date() : LocalDate.now();
            PostingRequest rerouted = applyClosedPeriodPolicy(request, effective);
            if (rerouted == null) {
                // Mise en quarantaine : rien n'entre au journal, la saisie
                // est conservée et attend le comptable.
                return Optional.empty();
            }
            request = rerouted;
        }

        // 3. Équilibre
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (JournalEntry e : request.entries()) {
            BigDecimal d = e.debitFcfa;
            BigDecimal c = e.creditFcfa;
            if ((d != null && c != null) || (d == null && c == null)) {
                throw new BusinessException(Messages.msg(
                        "m.acc-entry-debit-or-credit", e.syscohadaAccount));
            }
            if (d != null) totalDebit = totalDebit.add(d);
            if (c != null) totalCredit = totalCredit.add(c);
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException(Messages.msg("m.acc-piece-unbalanced",
                    String.valueOf(totalDebit), String.valueOf(totalCredit),
                    request.sourceRef()));
        }

        // Imputation analytique : un centre de coût ne se pose que sur une
        // ligne de charge (compte de classe 6), conformément à la règle
        // analytique (backlog CPT-09).
        for (JournalEntry e : request.entries()) {
            if (e.costCenter != null
                    && (e.syscohadaAccount == null || !e.syscohadaAccount.startsWith("6"))) {
                throw new BusinessException(Messages.msg(
                        "m.acc-cost-center-charge-only", e.syscohadaAccount));
            }
            boolean chargeOrProduct = e.syscohadaAccount != null
                    && (e.syscohadaAccount.startsWith("6") || e.syscohadaAccount.startsWith("7"));
            if (e.program != null && !chargeOrProduct) {
                throw new BusinessException(Messages.msg(
                        "m.acc-program-charge-or-revenue-only", e.syscohadaAccount));
            }
            if (e.project != null && e.program == null) {
                throw new BusinessException(Messages.msg(
                        "m.acc-project-requires-program", e.syscohadaAccount));
            }
        }

        // 4. Construction + insert
        JournalPieceEntity piece = new JournalPieceEntity();
        piece.id = idGenerator.newId();
        piece.ref = refService.next();
        piece.date = request.date() != null ? request.date() : LocalDate.now();
        // Toute la comptabilité passe ici : c'est le point où l'axe campagne
        // devient exploitable, et sans lui aucun compte d'exploitation de
        // campagne n'est possible.
        CampaignEntity campaign = campaignResolver.resolveOptionalForDate(piece.date, null);
        piece.campaignId = campaign != null ? campaign.id : null;
        piece.campaignYear = campaign != null ? campaign.campaignYear : null;
        piece.sourceType = request.sourceType();
        piece.sourceId = request.sourceId();
        piece.sourceRef = request.sourceRef();
        piece.libelle = request.libelle();
        piece.entries = new ArrayList<>(request.entries());
        piece.totalDebitFcfa = totalDebit;
        piece.totalCreditFcfa = totalCredit;
        piece.createdAt = Instant.now();
        piece.createdBy = actorUserId();
        piece.createdByEmail = null;
        try {
            pieces.insert(piece);
        } catch (com.mongodb.MongoWriteException dup) {
            if (dup.getError().getCategory()
                    != com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                throw dup;
            }
            // Course perdue sur l'index unique (sourceType, sourceId) :
            // l'autre appel a déjà comptabilisé — on renvoie sa pièce.
            LOG.infof("Course d'idempotence sur %s/%s : pièce existante renvoyée",
                    request.sourceType(), request.sourceId());
            return pieces.findBySource(request.sourceType(), request.sourceId());
        }
        LOG.infof("Pièce %s comptabilisée : %s %s : %s",
                piece.ref, piece.sourceType, piece.sourceRef, totalDebit);
        return Optional.of(piece);
    }

    /**
     * Crée une pièce miroir (débit↔crédit inversés) pour annuler une
     * pièce précédemment comptabilisée. Si la pièce originale n'existe
     * pas, no-op (cas d'un cancel sur un BC qui n'avait jamais été
     * livré, donc jamais comptabilisé). Si une contre-passation existe
     * déjà pour ce couple, no-op (idempotent).
     */
    public Optional<JournalPieceEntity> reverseFrom(PostingSourceType originalType,
                                                    UUID sourceId,
                                                    String reason) {
        Optional<JournalPieceEntity> original = pieces.findBySource(originalType, sourceId);
        if (original.isEmpty()) {
            LOG.debugf("Aucune pièce à contre-passer pour %s/%s : no-op", originalType, sourceId);
            return Optional.empty();
        }
        JournalPieceEntity src = original.get();
        PostingSourceType reversalType = reversalTypeFor(originalType);

        List<JournalEntry> mirrored = new ArrayList<>(src.entries.size());
        for (JournalEntry e : src.entries) {
            if (e.debitFcfa != null) {
                mirrored.add(JournalEntry.credit(e.syscohadaAccount, e.libelle, e.debitFcfa));
            } else {
                mirrored.add(JournalEntry.debit(e.syscohadaAccount, e.libelle, e.creditFcfa));
            }
        }
        String libelle = "Contre-passation " + src.ref
                + (reason != null && !reason.isBlank() ? " : " + reason : "");

        // L'idempotence est gérée par postPiece via l'index (sourceType, sourceId).
        Optional<JournalPieceEntity> created = postPiece(new PostingRequest(
                LocalDate.now(),
                reversalType,
                sourceId,
                src.sourceRef,
                libelle,
                mirrored
        ));
        created.ifPresent(p -> {
            // On lit puis on remplace pour poser le lien vers l'originale.
            p.reversedFromPieceId = src.id;
            // Pas de PUT sur le repo : on accepte le mini round-trip via re-insert.
            // Note : la pièce a déjà été insérée avec reversedFromPieceId=null. Pour
            // éviter une replace (qu'on ne veut pas ouvrir pour préserver l'immuabilité),
            // on l'enregistre via insertOne avec le bon champ. Comme on ne peut pas, on
            // documente : reversedFromPieceId est posé via une remontée par sourceId
            // côté repository.findAllForSource(). Si le lien explicite devient un
            // requirement, ajouter une méthode dédiée au repo.
        });
        return created;
    }


    /**
     * Compte de charge d'une ligne d'achat (backlog CPT-11) : le compte
     * porté par la fiche article prime ; à défaut, résolution par type
     * d'article (601/604/6081/624). Le cache évite de relire le même
     * article pour chaque ligne d'un BC.
     */
    private String chargeAccountFor(UUID articleId, ArticleType articleType,
                                    Map<UUID, String> cache) {
        if (articleId != null) {
            String resolved = cache.computeIfAbsent(articleId, id ->
                    articles.findById(id)
                            .map(a -> a.purchaseChargeAccount)
                            .filter(acc -> acc != null && !acc.isBlank())
                            .map(String::trim)
                            .orElse(""));
            if (!resolved.isEmpty()) return resolved;
        }
        return SyscohadaAccounts.purchaseChargeAccountFor(articleType);
    }

    /**
     * Centre de coût imputé par défaut à une ligne d'achat (backlog
     * CPT-09) : lu sur la fiche article, {@code null} si l'article n'en
     * porte pas. Cache par article pour ne pas relire à chaque ligne.
     */
    private String costCenterFor(UUID articleId, Map<UUID, String> cache) {
        if (articleId == null) return null;
        String resolved = cache.computeIfAbsent(articleId, id ->
                articles.findById(id)
                        .map(a -> a.defaultCostCenter)
                        .filter(cc -> cc != null && !cc.isBlank())
                        .map(String::trim)
                        .orElse(""));
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * Lignes de crédit 701 d'une vente, ventilées par programme budgétaire
     * de l'article (backlog CPT-10). Somme garantie égale à {@code ht}.
     */
    private List<JournalEntry> salesRevenueEntries(SaleEntity sale, BigDecimal ht) {
        // Cache par article : [compte de produit, programme, projet].
        Map<UUID, String[]> articleCache = new java.util.HashMap<>();
        // Groupe (compte|programme|projet) -> HT cumulé, ordre préservé.
        java.util.LinkedHashMap<String, BigDecimal> byGroup = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> groupMeta = new java.util.HashMap<>();
        BigDecimal sumHt = BigDecimal.ZERO;
        for (SaleLine line : sale.lines) {
            BigDecimal lineHt = nz(line.lineTotalHtFcfa);
            if (lineHt.signum() == 0) continue;
            String[] apg = articleCache.computeIfAbsent(line.articleId, id -> {
                if (id == null) {
                    return new String[]{SyscohadaAccounts.VENTES_PRODUITS_FINIS, null, null};
                }
                return articles.findById(id)
                        .map(a -> new String[]{
                                blankNull(a.salesRevenueAccount) != null
                                        ? a.salesRevenueAccount
                                        // Marchandise revendue en l'état ou produit
                                        // issu de la transformation : deux comptes
                                        // distincts, que le cabinet attend séparés.
                                        : SyscohadaAccounts.saleRevenueAccountFor(parseArticleType(a.type)),
                                blankNull(a.defaultProgram), blankNull(a.defaultProject)})
                        .orElse(new String[]{SyscohadaAccounts.VENTES_PRODUITS_FINIS, null, null});
            });
            String key = apg[0] + "|" + (apg[1] == null ? "" : apg[1]) + "|" + (apg[2] == null ? "" : apg[2]);
            byGroup.merge(key, lineHt, BigDecimal::add);
            groupMeta.putIfAbsent(key, apg);
            sumHt = sumHt.add(lineHt);
        }
        List<JournalEntry> result = new ArrayList<>();
        if (byGroup.isEmpty() || sumHt.signum() == 0) {
            result.add(JournalEntry.credit(SyscohadaAccounts.VENTES_PRODUITS_FINIS,
                    "Vente " + sale.ref, ht));
            return result;
        }
        // Coefficient pour absorber la remise globale (ht peut être < sumHt).
        BigDecimal running = BigDecimal.ZERO;
        List<String> keys = new ArrayList<>(byGroup.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            BigDecimal share = i == keys.size() - 1
                    ? ht.subtract(running)
                    : byGroup.get(key).multiply(ht)
                        .divide(sumHt, MONEY_SCALE, RoundingMode.HALF_UP);
            running = running.add(share);
            String[] apg = groupMeta.get(key);
            result.add(JournalEntry.credit(apg[0], "Vente " + sale.ref, share)
                    .program(apg[1], apg[2]));
        }
        return result;
    }

    private static String blankNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }

    /**
     * Impute une ligne de charge (backlog CPT-09/CPT-10) : centre de coût
     * depuis la fiche article, puis programme/projet dérivés du centre
     * (règle éditable portée par {@code CostCenter.defaultProgram}). Le
     * cache {@code centersByCode} évite de relire le référentiel.
     */
    /**
     * Avance de fonds à un délégué collecteur (backlog ACH-02) : débit du
     * compte d'avances (4091 paramétrable), crédit de la trésorerie.
     * Idempotent sur {@code (COLLECTOR_ADVANCE, advanceId)}.
     */
    /**
     * Décaissement d'un crédit ou d'une avance à un producteur membre :
     * débit du compte de créance sur le producteur, crédit de la
     * trésorerie. Le remboursement, lui, se constate à la livraison, par
     * une contrepartie sur ce même compte de créance.
     */
    /**
     * Sortie de fonds vers un autre compte de trésorerie : le montant
     * quitte le compte d'origine pour le compte de virements internes, où
     * il reste tant que la réception n'est pas confirmée.
     */
    public Optional<JournalPieceEntity> postTreasuryTransferOut(
            UUID transferId, String ref, String fromAccount, String fromLabel,
            BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(SyscohadaAccounts.VIREMENTS_INTERNES, "Fonds en transit " + ref, amount),
                JournalEntry.credit(fromAccount, "Sortie " + nullSafe(fromLabel), amount));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.TREASURY_TRANSFER, transferId, ref,
                "Transport de fonds " + ref + " : sortie", entries));
    }

    /**
     * Réception des fonds. Le compte de virements internes se solde du
     * montant parti ; si l'arrivée diffère, l'écart est constaté
     * immédiatement, faute de quoi le compte de passage traînerait un
     * résidu que personne ne saurait plus expliquer à la clôture.
     */
    public Optional<JournalPieceEntity> postTreasuryTransferIn(
            UUID transferId, String ref, String toAccount, String toLabel,
            BigDecimal sent, BigDecimal received, String discrepancyAccount, LocalDate date) {
        if (sent == null || sent.signum() <= 0) return Optional.empty();
        BigDecimal receivedAmount = received != null ? received : sent;
        BigDecimal gap = receivedAmount.subtract(sent);
        List<JournalEntry> entries = new ArrayList<>();
        if (receivedAmount.signum() > 0) {
            entries.add(JournalEntry.debit(toAccount, "Entrée " + nullSafe(toLabel), receivedAmount));
        }
        if (gap.signum() < 0) {
            entries.add(JournalEntry.debit(discrepancyAccount,
                    "Manquant sur transport " + ref, gap.negate()));
        }
        entries.add(JournalEntry.credit(SyscohadaAccounts.VIREMENTS_INTERNES,
                "Fonds reçus " + ref, sent));
        if (gap.signum() > 0) {
            entries.add(JournalEntry.credit(discrepancyAccount,
                    "Excédent sur transport " + ref, gap));
        }
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.TREASURY_TRANSFER_IN, transferId, ref + "-R",
                "Transport de fonds " + ref + " : réception", entries));
    }

    /**
     * Régularisation d'un écart de caisse constaté au comptage. L'écriture
     * aligne la comptabilité sur ce qui est physiquement présent.
     */
    public Optional<JournalPieceEntity> postFromCashCount(
            UUID countId, String ref, String cashAccount, String cashLabel,
            BigDecimal discrepancy, String discrepancyAccount, LocalDate date) {
        if (discrepancy == null || discrepancy.signum() == 0) return Optional.empty();
        List<JournalEntry> entries = discrepancy.signum() < 0
                ? List.of(
                        JournalEntry.debit(discrepancyAccount, "Manquant de caisse " + ref,
                                discrepancy.negate()),
                        JournalEntry.credit(cashAccount, nullSafe(cashLabel), discrepancy.negate()))
                : List.of(
                        JournalEntry.debit(cashAccount, nullSafe(cashLabel), discrepancy),
                        JournalEntry.credit(discrepancyAccount, "Excédent de caisse " + ref, discrepancy));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.CASH_COUNT, countId, ref,
                "Point de caisse " + ref + " : régularisation", entries));
    }

    public Optional<JournalPieceEntity> postFromMemberCredit(
            UUID creditId, String ref, String memberName,
            String creditAccount, String treasuryAccount,
            BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(creditAccount, "Créance sur " + nullSafe(memberName), amount),
                JournalEntry.credit(treasuryAccount, "Décaissement " + ref, amount));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.MEMBER_CREDIT, creditId, ref,
                "Crédit producteur " + ref + " : " + nullSafe(memberName), entries));
    }

    public Optional<JournalPieceEntity> postFromCollectorAdvance(
            UUID advanceId, String ref, String delegateLabel, BigDecimal amount,
            com.ntech.cabosse.reception.entity.PaymentMethod method, LocalDate date) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        String advanceAccount = preferencesLookup.current().collectorAdvanceAccount();
        String treasury = treasuryAccountFor(method);
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(advanceAccount, "Avance " + nullSafe(delegateLabel), amount),
                JournalEntry.credit(treasury, "Décaissement avance " + ref, amount));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.COLLECTOR_ADVANCE, advanceId, ref,
                "Avance délégué " + ref + " : " + nullSafe(delegateLabel), entries));
    }

    /**
     * Livraison d'un délégué imputée sur son avance (backlog ACH-02) :
     * débit du compte de charge d'achat (imputé par centre/programme),
     * crédit du compte d'avances (apurement). Idempotent sur
     * {@code (COLLECTOR_DELIVERY, deliveryId)}.
     */
    /**
     * Achat de matière première au producteur membre (reçu, backlog NEG-01) :
     * débit du compte de charge d'achat de l'article (imputé par centre/
     * programme), crédit du {@code creditAccount} — trésorerie (571/521 selon
     * le mode) pour un reçu direct, ou compte d'avances (4091) si le reçu est
     * rattaché à une avance délégué. Pas de TVA (producteurs non assujettis).
     */
    /** Une contrepartie de l'achat producteur : compte, libellé, montant. */
    public record PurchaseLeg(String account, String label, BigDecimal amount) {}

    /**
     * Reçu d'achat producteur : débit du compte de charge de l'article pour
     * le montant dû, crédité par une ou plusieurs contreparties selon qui a
     * réglé (trésorerie, compte d'avance du délégué, dette envers le
     * producteur si le paiement est partiel).
     *
     * <p>La rémunération du délégué, quand elle existe, s'ajoute en deux
     * lignes sur la même pièce : charge de rémunération au débit, compte du
     * délégué au crédit. Elle réduit d'autant ce qu'il doit à la
     * coopérative.</p>
     */
    public Optional<JournalPieceEntity> postFromProducerPurchase(
            UUID purchaseId, String ref, UUID articleId, ArticleType articleType,
            String articleName, BigDecimal amount, LocalDate date,
            List<PurchaseLeg> credits, PurchaseLeg marginCharge, PurchaseLeg marginCredit) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        String chargeAccount = chargeAccountFor(articleId, articleType, new java.util.HashMap<>());
        JournalEntry charge = imputeCharge(
                JournalEntry.debit(chargeAccount, "Achat producteur " + nullSafe(articleName), amount),
                articleId, new java.util.HashMap<>(), costCenters.byCode());
        List<JournalEntry> entries = new ArrayList<>();
        entries.add(charge);
        for (PurchaseLeg leg : credits) {
            if (leg == null || leg.amount() == null || leg.amount().signum() <= 0) continue;
            entries.add(JournalEntry.credit(leg.account(), leg.label(), leg.amount()));
        }
        if (marginCharge != null && marginCharge.amount() != null
                && marginCharge.amount().signum() > 0 && marginCredit != null) {
            entries.add(JournalEntry.debit(
                    marginCharge.account(), marginCharge.label(), marginCharge.amount()));
            entries.add(JournalEntry.credit(
                    marginCredit.account(), marginCredit.label(), marginCredit.amount()));
        }
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.PRODUCER_PURCHASE, purchaseId, ref,
                "Achat producteur " + ref, entries));
    }

    /**
     * Règlement d'une ou plusieurs livraisons producteur : débit du compte
     * de dette constitué au reçu, crédit de la trésorerie selon le mode de
     * paiement.
     *
     * <p>Une livraison peut se régler en plusieurs fois : chaque versement
     * porte sa propre pièce, et le compte de dette se solde par
     * accumulation. Rien ne se compense en silence.</p>
     */
    public Optional<JournalPieceEntity> postFromProducerPayment(
            UUID paymentId, String ref, String beneficiaryName, String debtAccount,
            com.ntech.cabosse.reception.entity.PaymentMethod method,
            BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(debtAccount, "Solde dû à " + nullSafe(beneficiaryName), amount),
                JournalEntry.credit(treasuryAccountFor(method), "Règlement " + ref, amount));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.PRODUCER_PAYMENT, paymentId, ref,
                "Règlement " + ref + " : " + nullSafe(beneficiaryName), entries));
    }

    /**
     * Vente de cacao en gros / export (backlog NEG-02) : débit 411 client TTC,
     * crédit du compte de vente de l'article (701) pour le HT (commercial +
     * primes au MVP), crédit TVA collectée (445700) si TVA &gt; 0. Le coût des
     * ventes n'est pas journalisé (dérivé du CMUP comme les ventes de PF).
     */
    public Optional<JournalPieceEntity> postFromCacaoSale(
            UUID saleId, String ref, String customerName, String revenueAccount,
            BigDecimal htAmount, BigDecimal vatAmount, LocalDate date) {
        BigDecimal ht = nz(htAmount);
        BigDecimal vat = nz(vatAmount);
        if (ht.signum() <= 0) return Optional.empty();
        BigDecimal ttc = ht.add(vat);
        String revenue = (revenueAccount == null || revenueAccount.isBlank())
                ? SyscohadaAccounts.VENTES_PRODUITS_FINIS : revenueAccount;
        List<JournalEntry> entries = new ArrayList<>();
        entries.add(JournalEntry.debit(SyscohadaAccounts.CLIENTS, "Créance " + nullSafe(customerName), ttc));
        entries.add(JournalEntry.credit(revenue, "Vente cacao " + ref, ht));
        if (vat.signum() > 0) {
            entries.add(JournalEntry.credit(SyscohadaAccounts.TVA_COLLECTEE, "TVA collectée " + ref, vat));
        }
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.CACAO_SALE, saleId, ref,
                "Vente cacao " + ref + " : " + nullSafe(customerName), entries));
    }

    public Optional<JournalPieceEntity> postFromCollectorDelivery(
            UUID deliveryId, String ref, UUID articleId, ArticleType articleType,
            String articleName, BigDecimal amount, LocalDate date) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        String advanceAccount = preferencesLookup.current().collectorAdvanceAccount();
        String chargeAccount = chargeAccountFor(articleId, articleType, new java.util.HashMap<>());
        JournalEntry charge = imputeCharge(
                JournalEntry.debit(chargeAccount, "Livraison " + nullSafe(articleName), amount),
                articleId, new java.util.HashMap<>(), costCenters.byCode());
        List<JournalEntry> entries = List.of(
                charge,
                JournalEntry.credit(advanceAccount, "Apurement avance " + ref, amount));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.COLLECTOR_DELIVERY, deliveryId, ref,
                "Livraison délégué " + ref, entries));
    }

    /**
     * Dépense directe sans bon de livraison (backlog ACH-03) : contrat/
     * abonnement ou petite caisse. Débit du compte de charge (HT) ; débit
     * TVA déductible si la TVA est récupérable et non nulle ; crédit du
     * compte de trésorerie (TTC) selon le mode de règlement. Si la TVA
     * n'est pas récupérable, elle est intégrée au débit de la charge.
     * Idempotent sur {@code (DIRECT_EXPENSE, expenseId)}.
     */
    public Optional<JournalPieceEntity> postFromDirectExpense(
            UUID expenseId, String ref, LocalDate date, String chargeAccount,
            String label, BigDecimal amountHt, BigDecimal vatAmount,
            BigDecimal amountTtc, String treasuryAccount, String allocationKeyCode) {
        if (amountTtc == null || amountTtc.signum() <= 0) return Optional.empty();
        boolean vatRecoverable = preferencesLookup.current().vatRecoverable();
        BigDecimal vat = nz(vatAmount);
        String piece = (label == null || label.isBlank()) ? "Dépense " + ref : label.trim();
        boolean separateVat = vatRecoverable && vat.signum() > 0;
        // Base de charge : HT si TVA récupérable (ligne TVA séparée), sinon TTC.
        BigDecimal chargeBase = separateVat ? nz(amountHt) : amountTtc;
        List<JournalEntry> entries = new ArrayList<>(
                chargeLines(chargeAccount, piece, chargeBase, allocationKeyCode));
        if (separateVat) {
            entries.add(JournalEntry.debit(preferencesLookup.current().vatDeductibleAccount(),
                    "TVA déductible", vat));
        }
        entries.add(JournalEntry.credit(treasuryAccount, "Règlement " + ref, amountTtc));
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.DIRECT_EXPENSE, expenseId, ref, piece, entries));
    }

    /**
     * Lignes de débit de charge : une seule ligne si pas de clé de
     * répartition, sinon la charge est éclatée sur les centres de coût de
     * la clé au prorata des poids (charge indirecte, backlog CPT-17).
     */
    private List<JournalEntry> chargeLines(String account, String label,
                                           BigDecimal base, String allocationKeyCode) {
        if (allocationKeyCode == null || allocationKeyCode.isBlank()) {
            List<JournalEntry> single = new ArrayList<>();
            single.add(JournalEntry.debit(account, label, base));
            return single;
        }
        com.ntech.cabosse.analytics.entity.AllocationKeyEntity key = allocationKeys
                .findByCode(allocationKeyCode)
                .orElseThrow(() -> new BusinessException(
                        Messages.msg("m.exp-allocation-key-not-found", allocationKeyCode)));
        if (key.lines == null || key.lines.isEmpty()) {
            List<JournalEntry> single = new ArrayList<>();
            single.add(JournalEntry.debit(account, label, base));
            return single;
        }
        return spreadCharge(account, label, base, key, costCenters.byCode());
    }

    /** Éclate une charge sur les centres de coût d'une clé, au prorata des poids. */
    private List<JournalEntry> spreadCharge(
            String account, String label, BigDecimal total,
            com.ntech.cabosse.analytics.entity.AllocationKeyEntity key,
            Map<String, com.ntech.cabosse.analytics.entity.CostCenterEntity> centersByCode) {
        List<JournalEntry> result = new ArrayList<>();
        BigDecimal sumW = BigDecimal.ZERO;
        for (var l : key.lines) sumW = sumW.add(nz(l.weight));
        if (sumW.signum() <= 0) {
            result.add(JournalEntry.debit(account, label, total));
            return result;
        }
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < key.lines.size(); i++) {
            var l = key.lines.get(i);
            BigDecimal share = (i == key.lines.size() - 1)
                    ? total.subtract(running)
                    : total.multiply(nz(l.weight)).divide(sumW, MONEY_SCALE, RoundingMode.HALF_UP);
            running = running.add(share);
            if (share.signum() == 0) continue;
            result.add(imputeChargeToCostCenter(
                    JournalEntry.debit(account, label, share), l.costCenter, centersByCode));
        }
        return result;
    }

    /** Impute un centre de coût (et son programme, règle v8) sur une ligne de charge. */
    private JournalEntry imputeChargeToCostCenter(
            JournalEntry entry, String ccCode,
            Map<String, com.ntech.cabosse.analytics.entity.CostCenterEntity> centersByCode) {
        if (ccCode == null || ccCode.isBlank()) return entry;
        entry.costCenter(ccCode);
        var center = centersByCode.get(ccCode);
        if (center != null && center.defaultProgram != null && !center.defaultProgram.isBlank()) {
            entry.program(center.defaultProgram, center.defaultProject);
        }
        return entry;
    }

    private JournalEntry imputeCharge(JournalEntry entry, UUID articleId,
                                      Map<UUID, String> ccCache,
                                      Map<String, com.ntech.cabosse.analytics.entity.CostCenterEntity> centersByCode) {
        String cc = costCenterFor(articleId, ccCache);
        entry.costCenter(cc);
        if (cc != null) {
            var center = centersByCode.get(cc);
            if (center != null && center.defaultProgram != null && !center.defaultProgram.isBlank()) {
                entry.program(center.defaultProgram, center.defaultProject);
            }
        }
        return entry;
    }

    private PostingSourceType reversalTypeFor(PostingSourceType t) {
        return switch (t) {
            case PURCHASE_ORDER -> PostingSourceType.PURCHASE_ORDER_REVERSAL;
            case DIRECT_RECEIPT -> PostingSourceType.DIRECT_RECEIPT_REVERSAL;
            case SALE -> PostingSourceType.SALE_REVERSAL;
            case SALE_PAYMENT -> PostingSourceType.SALE_PAYMENT_REVERSAL;
            case DIRECT_RECEIPT_PAYMENT -> PostingSourceType.DIRECT_RECEIPT_PAYMENT_REVERSAL;
            case MEMBER_CAPITAL -> PostingSourceType.MEMBER_CAPITAL_REVERSAL;
            case MEMBER_CAPITAL_LIBERATION -> PostingSourceType.MEMBER_CAPITAL_LIBERATION_REVERSAL;
            case COLLECTOR_ADVANCE -> PostingSourceType.COLLECTOR_ADVANCE_REVERSAL;
            case DIRECT_EXPENSE -> PostingSourceType.DIRECT_EXPENSE_REVERSAL;
            default -> throw new BusinessException(
                    Messages.msg("m.acc-piece-already-reversed", t));
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers — construction des PostingRequest depuis les agrégats métier
    // ════════════════════════════════════════════════════════════════

    /**
     * BC livré : pour chaque ligne, débit compte de charge (selon type
     * d'article) ; débit 4456 si TVA récupérable ; crédit 401 TTC global.
     *
     * <p>Cas TVA non récupérable : on intègre la TVA dans le débit des
     * comptes de charges au prorata HT de chaque ligne. Le
     * crédit 401 reste TTC.</p>
     */
    public Optional<JournalPieceEntity> postFromPurchaseOrder(PurchaseOrderEntity bc, boolean vatRecoverable) {
        List<JournalEntry> entries = new ArrayList<>();
        Map<UUID, String> accountCache = new java.util.HashMap<>();
        Map<UUID, String> ccCache = new java.util.HashMap<>();
        Map<String, com.ntech.cabosse.analytics.entity.CostCenterEntity> centersByCode = costCenters.byCode();
        BigDecimal vatRate = nz(bc.vatRatePct);
        BigDecimal totalTtc = nz(bc.totalTtcFcfa);

        if (vatRecoverable && vatRate.signum() > 0 && nz(bc.vatFcfa).signum() > 0) {
            // Débit TVA déductible globale (compte paramétrable, défaut 44566)
            entries.add(JournalEntry.debit(
                    preferencesLookup.current().vatDeductibleAccount(),
                    "TVA déductible " + vatRate + "%",
                    nz(bc.vatFcfa)
            ));
            // Débit comptes de charges = HT par ligne
            for (PurchaseOrderLine line : bc.lines) {
                BigDecimal lineHt = nz(line.totalLineFcfa);
                if (lineHt.signum() == 0) continue;
                String account = chargeAccountFor(line.articleId, parseArticleType(line.articleType), accountCache);
                entries.add(imputeCharge(JournalEntry.debit(account, libelleLine(line), lineHt),
                        line.articleId, ccCache, centersByCode));
            }
        } else {
            // TVA non récupérable → on enrichit chaque ligne au prorata HT par le coefficient (1 + vatRate/100).
            BigDecimal coefficient = vatRate.signum() > 0
                    ? BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), VAT_RATIO_SCALE, RoundingMode.HALF_UP))
                    : BigDecimal.ONE;
            BigDecimal sumEnriched = BigDecimal.ZERO;
            List<JournalEntry> lineEntries = new ArrayList<>();
            for (PurchaseOrderLine line : bc.lines) {
                BigDecimal lineHt = nz(line.totalLineFcfa);
                if (lineHt.signum() == 0) continue;
                BigDecimal enriched = lineHt.multiply(coefficient).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                sumEnriched = sumEnriched.add(enriched);
                String account = chargeAccountFor(line.articleId, parseArticleType(line.articleType), accountCache);
                lineEntries.add(imputeCharge(JournalEntry.debit(account, libelleLine(line), enriched),
                        line.articleId, ccCache, centersByCode));
            }
            // Ajustement d'arrondi cumulé : aligne la dernière ligne sur le TTC exact.
            if (!lineEntries.isEmpty() && sumEnriched.compareTo(totalTtc) != 0) {
                JournalEntry last = lineEntries.get(lineEntries.size() - 1);
                BigDecimal diff = totalTtc.subtract(sumEnriched);
                last.debitFcfa = last.debitFcfa.add(diff);
            }
            entries.addAll(lineEntries);
        }

        entries.add(JournalEntry.credit(
                SyscohadaAccounts.FOURNISSEURS,
                "Dette " + bc.supplierName,
                totalTtc
        ));

        return postPiece(new PostingRequest(
                bc.deliveryDate != null ? bc.deliveryDate : LocalDate.now(),
                PostingSourceType.PURCHASE_ORDER,
                bc.id,
                bc.ref,
                "Livraison " + bc.ref + " : " + nullSafe(bc.supplierName),
                entries
        ));
    }

    /**
     * RD livrée : article unique, plusieurs lignes-fournisseurs. Une seule
     * pièce par RD, avec un débit charge agrégé par compte (typiquement
     * 601 puisque la RD ne porte qu'un article) + crédit 401 par
     * fournisseur (chaque ligne = un fournisseur distinct).
     */
    public Optional<JournalPieceEntity> postFromDirectReceipt(DirectReceiptEntity rd, ArticleType articleType, boolean vatRecoverable) {
        List<JournalEntry> entries = new ArrayList<>();
        BigDecimal vatRate = nz(rd.vatRatePct);
        BigDecimal coefficient = (!vatRecoverable && vatRate.signum() > 0)
                ? BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), VAT_RATIO_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE;

        BigDecimal totalCharge = BigDecimal.ZERO;
        BigDecimal totalDue = BigDecimal.ZERO;
        for (DirectReceiptLine line : rd.lines) {
            BigDecimal lineHt = nz(line.totalLineFcfa);
            if (lineHt.signum() == 0) continue;
            BigDecimal lineTtc = lineHt.multiply(coefficient).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            totalCharge = totalCharge.add(vatRecoverable ? lineHt : lineTtc);
            totalDue = totalDue.add(lineTtc);
            // Crédit 401 par ligne (pour préserver l'analytique par fournisseur).
            entries.add(JournalEntry.credit(
                    SyscohadaAccounts.FOURNISSEURS,
                    "Dette " + nullSafe(line.supplierName),
                    lineTtc
            ));
        }

        // Débit charge agrégé (même compte 601/604/6081 selon le type)
        String account = chargeAccountFor(rd.articleId, articleType, new java.util.HashMap<>());
        entries.add(0, imputeCharge(
                JournalEntry.debit(account, "Achat " + nullSafe(rd.articleName), totalCharge),
                rd.articleId, new java.util.HashMap<>(), costCenters.byCode()));

        // TVA déductible si applicable
        if (vatRecoverable && vatRate.signum() > 0) {
            BigDecimal vatAmount = totalDue.subtract(totalCharge).max(BigDecimal.ZERO);
            if (vatAmount.signum() > 0) {
                entries.add(1, JournalEntry.debit(
                        preferencesLookup.current().vatDeductibleAccount(),
                        "TVA déductible " + vatRate + "%",
                        vatAmount
                ));
            }
        }

        return postPiece(new PostingRequest(
                rd.receivedDate != null ? rd.receivedDate : LocalDate.now(),
                PostingSourceType.DIRECT_RECEIPT,
                rd.id,
                rd.ref,
                "Réception " + rd.ref + " : " + nullSafe(rd.articleName),
                entries
        ));
    }

    /**
     * Vente confirmée : débit 411 TTC ; crédit 701 HT (agrégé, MVP) ;
     * crédit 4457 si TVA > 0.
     */
    public Optional<JournalPieceEntity> postFromSale(SaleEntity sale) {
        List<JournalEntry> entries = new ArrayList<>();
        BigDecimal ht = nz(sale.subtotalHtFcfa).subtract(nz(sale.discountFcfa));
        BigDecimal vat = nz(sale.vatFcfa);
        BigDecimal ttc = nz(sale.totalTtcFcfa);

        entries.add(JournalEntry.debit(
                SyscohadaAccounts.CLIENTS,
                "Créance " + nullSafe(sale.customerName),
                ttc
        ));
        // Crédit 701 ventilé par programme budgétaire (backlog CPT-10) :
        // le programme d'un produit est porté par la fiche article
        // (une vente n'a pas de centre de coût). Les lignes sont groupées
        // par (programme, projet) ; la remise globale est absorbée par un
        // coefficient et l'écart d'arrondi aligné sur la dernière ligne.
        entries.addAll(salesRevenueEntries(sale, ht));
        if (vat.signum() > 0) {
            entries.add(JournalEntry.credit(
                    SyscohadaAccounts.TVA_COLLECTEE,
                    "TVA collectée " + nz(sale.vatRatePct) + "%",
                    vat
            ));
        }

        return postPiece(new PostingRequest(
                sale.saleDate != null ? sale.saleDate : LocalDate.now(),
                PostingSourceType.SALE,
                sale.id,
                sale.ref,
                "Vente " + sale.ref + " : " + nullSafe(sale.customerName),
                entries
        ));
    }

    /** Paiement vente : débit trésorerie (521/571 selon méthode) + crédit 411. */
    public Optional<JournalPieceEntity> postFromSalePayment(SaleEntity sale, SalePayment payment) {
        String treasury = treasuryAccountFor(payment.method);
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(treasury, "Encaissement " + sale.ref, nz(payment.amountFcfa)),
                JournalEntry.credit(SyscohadaAccounts.CLIENTS, "Apurement " + nullSafe(sale.customerName), nz(payment.amountFcfa))
        );
        return postPiece(new PostingRequest(
                payment.paidOn != null ? payment.paidOn : LocalDate.now(),
                PostingSourceType.SALE_PAYMENT,
                payment.id,
                sale.ref,
                "Encaissement " + sale.ref + " : " + nullSafe(sale.customerName),
                entries
        ));
    }

    /**
     * Paiement RD : débit 401 (apurement dette fournisseur) + crédit
     * trésorerie. L'idempotence se fait sur le couple
     * {@code (sourceType=DIRECT_RECEIPT_PAYMENT, paymentId)} ; on
     * synthétise un paymentId à partir de {@code rd.id + lineId} car le
     * modèle actuel n'a pas d'UUID propre sur DirectReceiptPayment.
     */
    /**
     * Identifiant déterministe d'un paiement RD pour l'idempotence
     * comptable. Le modèle n'a pas d'UUID propre sur
     * {@code DirectReceiptPayment} — on synthétise à partir de
     * {@code rdId + lineId}, ce qui suffit puisqu'une ligne RD ne
     * porte qu'un paiement à la fois.
     */
    public static UUID directReceiptPaymentSurrogateId(UUID rdId, UUID lineId) {
        return UUID.nameUUIDFromBytes((rdId + ":" + lineId).getBytes());
    }

    public Optional<JournalPieceEntity> postFromDirectReceiptPayment(DirectReceiptEntity rd, DirectReceiptLine line) {
        if (line.payment == null) return Optional.empty();
        String treasury = treasuryAccountFor(line.payment.method);
        UUID paymentSurrogateId = directReceiptPaymentSurrogateId(rd.id, line.id);
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(SyscohadaAccounts.FOURNISSEURS, "Apurement " + nullSafe(line.supplierName), nz(line.payment.amountFcfa)),
                JournalEntry.credit(treasury, "Décaissement " + rd.ref, nz(line.payment.amountFcfa))
        );
        return postPiece(new PostingRequest(
                line.payment.paidOn != null ? line.payment.paidOn : LocalDate.now(),
                PostingSourceType.DIRECT_RECEIPT_PAYMENT,
                paymentSurrogateId,
                rd.ref,
                "Décaissement " + rd.ref + " : " + nullSafe(line.supplierName),
                entries
        ));
    }

    /**
     * Écart de valeur d'inventaire agrégé par nature d'article.
     * {@code deltaValueFcfa} signé : positif = boni (compté supérieur au
     * théorique), négatif = mali.
     */
    public record InventoryValueDelta(ArticleType articleType, BigDecimal deltaValueFcfa) {}

    /**
     * Régularisation d'inventaire : pour chaque nature d'article, boni =
     * débit compte de stock / crédit variation ; mali = inverse. L'écart
     * est valorisé au CMUP figé à l'ouverture de la session.
     */
    public Optional<JournalPieceEntity> postFromInventorySession(UUID sessionId,
                                                                 String sessionRef,
                                                                 LocalDate date,
                                                                 List<InventoryValueDelta> deltas) {
        List<JournalEntry> entries = new ArrayList<>();
        for (InventoryValueDelta delta : deltas) {
            BigDecimal value = nz(delta.deltaValueFcfa()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (value.signum() == 0) continue;
            String stockAccount = SyscohadaAccounts.stockAccountFor(delta.articleType());
            String variationAccount = SyscohadaAccounts.stockVariationAccountFor(delta.articleType());
            if (stockAccount == null || variationAccount == null) continue;
            String label = "Écart d'inventaire " + sessionRef;
            if (value.signum() > 0) {
                entries.add(JournalEntry.debit(stockAccount, label, value));
                entries.add(JournalEntry.credit(variationAccount, label, value));
            } else {
                BigDecimal abs = value.abs();
                entries.add(JournalEntry.debit(variationAccount, label, abs));
                entries.add(JournalEntry.credit(stockAccount, label, abs));
            }
        }
        if (entries.isEmpty()) return Optional.empty();
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.INVENTORY_ADJUSTMENT,
                sessionId,
                sessionRef,
                "Régularisation d'inventaire " + sessionRef,
                entries
        ));
    }

    /**
     * Part sociale versée par un membre à la validation de son adhésion
     * (backlog MEM-02) : débit trésorerie, crédit compte capital du
     * tenant. Idempotent sur {@code (MEMBER_CAPITAL, memberId)}.
     */
    public Optional<JournalPieceEntity> postFromMemberCapital(UUID memberId,
                                                              String memberLabel,
                                                              BigDecimal amount,
                                                              LocalDate date,
                                                              String capitalAccount,
                                                              String treasuryAccount) {
        if (amount == null || amount.signum() <= 0) return Optional.empty();
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(treasuryAccount, "Part sociale " + nullSafe(memberLabel), amount),
                JournalEntry.credit(capitalAccount, "Capital souscrit " + nullSafe(memberLabel), amount)
        );
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.MEMBER_CAPITAL,
                memberId,
                nullSafe(memberLabel),
                "Part sociale : " + nullSafe(memberLabel),
                entries
        ));
    }

    /**
     * Cycle « souscription puis libération » des parts sociales
     * (préférence {@code memberCapitalFlow = SUBSCRIPTION}, réf. jeux
     * d'écritures v7) : pièce de souscription (débit 461, crédit compte
     * capital) puis pièce de libération (débit trésorerie, crédit 461).
     * Chaque pièce est idempotente sur son couple (sourceType, memberId).
     */
    public void postFromMemberCapitalSubscription(UUID memberId,
                                                  String memberLabel,
                                                  BigDecimal amount,
                                                  LocalDate date,
                                                  String capitalAccount,
                                                  String treasuryAccount) {
        if (amount == null || amount.signum() <= 0) return;
        LocalDate d = date != null ? date : LocalDate.now();
        postPiece(new PostingRequest(
                d,
                PostingSourceType.MEMBER_CAPITAL,
                memberId,
                nullSafe(memberLabel),
                "Souscription part sociale : " + nullSafe(memberLabel),
                List.of(
                        JournalEntry.debit(SyscohadaAccounts.ASSOCIES_CAPITAL,
                                "Souscription " + nullSafe(memberLabel), amount),
                        JournalEntry.credit(capitalAccount,
                                "Capital souscrit " + nullSafe(memberLabel), amount)
                )
        ));
        postPiece(new PostingRequest(
                d,
                PostingSourceType.MEMBER_CAPITAL_LIBERATION,
                memberId,
                nullSafe(memberLabel),
                "Libération part sociale : " + nullSafe(memberLabel),
                List.of(
                        JournalEntry.debit(treasuryAccount,
                                "Libération part sociale " + nullSafe(memberLabel), amount),
                        JournalEntry.credit(SyscohadaAccounts.ASSOCIES_CAPITAL,
                                "Libération " + nullSafe(memberLabel), amount)
                )
        ));
    }

    /**
     * Traçabilité d'un transfert de stock inter-sites (backlog STK-01,
     * activée par la préférence tenant {@code postStockTransferEntries}).
     * Le plan MVP ne tient pas de sous-comptes de stock par site : la
     * pièce mouvemente le même compte de stock au débit et au crédit,
     * les libellés portant les sites — trace au journal sans effet sur
     * la balance. Des sous-comptes par site affineront le schéma si
     * l'expert-comptable du tenant le demande.
     */
    /**
     * Requalification d'une quantité d'une nature à une autre : la charge
     * d'achat quitte le compte de la nature d'origine pour celui de la
     * nature d'arrivée. Rien n'entre ni ne sort de l'entreprise, seule la
     * destination du bien change, donc le résultat est inchangé et seule
     * la ventilation bouge.
     */
    public Optional<JournalPieceEntity> postFromStockReclassification(
            UUID reclassificationId, ArticleType fromType, ArticleType toType,
            String fromName, String toName, BigDecimal valueFcfa, LocalDate date) {
        if (valueFcfa == null || valueFcfa.signum() <= 0) return Optional.empty();
        String fromAccount = SyscohadaAccounts.purchaseChargeAccountFor(fromType);
        String toAccount = SyscohadaAccounts.purchaseChargeAccountFor(toType);
        if (fromAccount.equals(toAccount)) return Optional.empty();
        BigDecimal value = valueFcfa.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(toAccount, "Requalification vers " + nullSafe(toName), value),
                JournalEntry.credit(fromAccount, "Requalification depuis " + nullSafe(fromName), value)
        );
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.STOCK_TRANSFER, reclassificationId,
                "REQ-" + reclassificationId.toString().substring(0, 8),
                "Requalification " + nullSafe(fromName) + " vers " + nullSafe(toName), entries));
    }

    public Optional<JournalPieceEntity> postFromStockTransfer(UUID transferId,
                                                              ArticleType articleType,
                                                              String articleName,
                                                              BigDecimal valueFcfa,
                                                              String fromSiteName,
                                                              String toSiteName,
                                                              LocalDate date) {
        if (valueFcfa == null || valueFcfa.signum() <= 0) return Optional.empty();
        String stockAccount = SyscohadaAccounts.stockAccountFor(articleType);
        if (stockAccount == null) return Optional.empty();
        BigDecimal value = valueFcfa.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<JournalEntry> entries = List.of(
                JournalEntry.debit(stockAccount,
                        "Stock " + nullSafe(toSiteName) + " : " + nullSafe(articleName), value),
                JournalEntry.credit(stockAccount,
                        "Stock " + nullSafe(fromSiteName) + " : " + nullSafe(articleName), value)
        );
        return postPiece(new PostingRequest(
                date != null ? date : LocalDate.now(),
                PostingSourceType.STOCK_TRANSFER,
                transferId,
                nullSafe(articleName),
                "Transfert " + nullSafe(fromSiteName) + " vers " + nullSafe(toSiteName)
                        + " : " + nullSafe(articleName),
                entries
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers internes
    // ════════════════════════════════════════════════════════════════

    public String treasuryAccountFor(PaymentMethod method) {
        if (method == null) return SyscohadaAccounts.BANQUE_DEFAULT;
        return switch (method) {
            case CASH -> SyscohadaAccounts.CAISSE_DEFAULT;
            // MOBILE_MONEY, TRANSFER, OTHER → banque par défaut au MVP ;
            // sera ventilé sur un BankAccount spécifique si le tenant
            // attache un bankAccountId au paiement (sprint ultérieur).
            default -> SyscohadaAccounts.BANQUE_DEFAULT;
        };
    }

    private ArticleType parseArticleType(String name) {
        if (name == null) return ArticleType.RAW_MATERIAL;
        try { return ArticleType.valueOf(name); }
        catch (IllegalArgumentException e) { return ArticleType.RAW_MATERIAL; }
    }

    private String libelleLine(PurchaseOrderLine line) {
        String code = nullSafe(line.articleCode);
        String name = nullSafe(line.designation);
        return code.isEmpty() ? name : code + " " + name;
    }

    private UUID actorUserId() {
        try { return tenantContext.userId(); }
        catch (Exception e) {
            // Hors HTTP (jobs internes) → user système.
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String nullSafe(String s) { return s == null ? "" : s; }
}
