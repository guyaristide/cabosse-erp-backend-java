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
import com.ntech.cabosse.sale.entity.SalePayment;
import com.ntech.cabosse.shared.exception.BusinessException;
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
 * <p><strong>Règle TVA non récupérable</strong> (cf. mémoire Tilo) : si
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
    @Inject JournalPieceRefService refService;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;

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
    public Optional<JournalPieceEntity> postPiece(PostingRequest request) {
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new BusinessException("Pièce comptable vide — au moins une écriture requise.");
        }

        // 1. Idempotence
        Optional<JournalPieceEntity> existing = pieces.findBySource(request.sourceType(), request.sourceId());
        if (existing.isPresent()) {
            LOG.debugf("Pièce déjà comptabilisée pour %s/%s — no-op",
                    request.sourceType(), request.sourceId());
            return existing;
        }

        // 2. Équilibre
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (JournalEntry e : request.entries()) {
            BigDecimal d = e.debitFcfa;
            BigDecimal c = e.creditFcfa;
            if ((d != null && c != null) || (d == null && c == null)) {
                throw new BusinessException(
                        "Écriture invalide : exactement un de débit/crédit doit être renseigné (compte "
                                + e.syscohadaAccount + ").");
            }
            if (d != null) totalDebit = totalDebit.add(d);
            if (c != null) totalCredit = totalCredit.add(c);
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException(
                    "Pièce déséquilibrée : débit " + totalDebit + " ≠ crédit " + totalCredit
                            + " (source " + request.sourceRef() + ").");
        }

        // 3. Construction + insert
        JournalPieceEntity piece = new JournalPieceEntity();
        piece.id = idGenerator.newId();
        piece.ref = refService.next();
        piece.date = request.date() != null ? request.date() : LocalDate.now();
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
        pieces.insert(piece);
        LOG.infof("Pièce %s comptabilisée — %s %s — %s",
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
            LOG.debugf("Aucune pièce à contre-passer pour %s/%s — no-op", originalType, sourceId);
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
                + (reason != null && !reason.isBlank() ? " — " + reason : "");

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

    private PostingSourceType reversalTypeFor(PostingSourceType t) {
        return switch (t) {
            case PURCHASE_ORDER -> PostingSourceType.PURCHASE_ORDER_REVERSAL;
            case DIRECT_RECEIPT -> PostingSourceType.DIRECT_RECEIPT_REVERSAL;
            case SALE -> PostingSourceType.SALE_REVERSAL;
            case SALE_PAYMENT -> PostingSourceType.SALE_PAYMENT_REVERSAL;
            case DIRECT_RECEIPT_PAYMENT -> PostingSourceType.DIRECT_RECEIPT_PAYMENT_REVERSAL;
            default -> throw new BusinessException(
                    "Contre-passation impossible sur une pièce déjà contre-passée (" + t + ").");
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
     * comptes de charges au prorata HT de chaque ligne (règle Tilo). Le
     * crédit 401 reste TTC.</p>
     */
    public Optional<JournalPieceEntity> postFromPurchaseOrder(PurchaseOrderEntity bc, boolean vatRecoverable) {
        List<JournalEntry> entries = new ArrayList<>();
        BigDecimal vatRate = nz(bc.vatRatePct);
        BigDecimal totalTtc = nz(bc.totalTtcFcfa);

        if (vatRecoverable && vatRate.signum() > 0 && nz(bc.vatFcfa).signum() > 0) {
            // Débit 4456 = TVA déductible globale
            entries.add(JournalEntry.debit(
                    SyscohadaAccounts.TVA_DEDUCTIBLE,
                    "TVA déductible " + vatRate + "%",
                    nz(bc.vatFcfa)
            ));
            // Débit comptes de charges = HT par ligne
            for (PurchaseOrderLine line : bc.lines) {
                BigDecimal lineHt = nz(line.totalLineFcfa);
                if (lineHt.signum() == 0) continue;
                String account = SyscohadaAccounts.purchaseChargeAccountFor(parseArticleType(line.articleType));
                entries.add(JournalEntry.debit(account, libelleLine(line), lineHt));
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
                String account = SyscohadaAccounts.purchaseChargeAccountFor(parseArticleType(line.articleType));
                lineEntries.add(JournalEntry.debit(account, libelleLine(line), enriched));
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
                "Livraison " + bc.ref + " — " + nullSafe(bc.supplierName),
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
        String account = SyscohadaAccounts.purchaseChargeAccountFor(articleType);
        entries.add(0, JournalEntry.debit(
                account,
                "Achat " + nullSafe(rd.articleName),
                totalCharge
        ));

        // TVA déductible si applicable
        if (vatRecoverable && vatRate.signum() > 0) {
            BigDecimal vatAmount = totalDue.subtract(totalCharge).max(BigDecimal.ZERO);
            if (vatAmount.signum() > 0) {
                entries.add(1, JournalEntry.debit(
                        SyscohadaAccounts.TVA_DEDUCTIBLE,
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
                "Réception " + rd.ref + " — " + nullSafe(rd.articleName),
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
        entries.add(JournalEntry.credit(
                SyscohadaAccounts.VENTES_PRODUITS_FINIS,
                "Vente " + sale.ref,
                ht
        ));
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
                "Vente " + sale.ref + " — " + nullSafe(sale.customerName),
                entries
        ));
    }

    /** Paiement vente : débit trésorerie (521/530 selon méthode) + crédit 411. */
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
                "Encaissement " + sale.ref + " — " + nullSafe(sale.customerName),
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
                "Décaissement " + rd.ref + " — " + nullSafe(line.supplierName),
                entries
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers internes
    // ════════════════════════════════════════════════════════════════

    private String treasuryAccountFor(PaymentMethod method) {
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
