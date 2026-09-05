package com.ntech.cabosse.dispatch.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.service.CampaignResolver;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.dispatch.dto.CreateDispatchNoteDto;
import com.ntech.cabosse.dispatch.dto.DispatchLineInputDto;
import com.ntech.cabosse.dispatch.dto.DispatchNoteResponseDto;
import com.ntech.cabosse.dispatch.entity.DispatchLine;
import com.ntech.cabosse.dispatch.entity.DispatchNoteEntity;
import com.ntech.cabosse.dispatch.entity.DispatchNoteStatus;
import com.ntech.cabosse.dispatch.repository.DispatchNoteRepository;
import com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity;
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
import com.ntech.cabosse.site.repository.SiteRepository;
import com.ntech.cabosse.stock.dto.MovementInput;
import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import com.ntech.cabosse.stock.service.StockService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bordereau de sortie (épic magasin, CE-195, modèle de l'expert du
 * 05/09/2026).
 *
 * <p>Le chargement appelle des reçus d'achat, au besoin partiellement :
 * chaque appel est un décrément conditionnel atomique sur le disponible
 * du reçu, deux chargements simultanés ne sortant jamais deux fois le
 * même reliquat. Chaque ligne sort du stock au CMUP photographié, avec le
 * lot du reçu : la traçabilité aval lit alors l'exact, plus l'inféré. La
 * vente appellera le bordereau (CE-194) et ne sortira plus de stock.</p>
 *
 * <p>Le brut d'une ligne se déduit du net et des sacs (brut = net + sacs,
 * la règle constante du carnet), le magasinier ne pesant pas une seconde
 * fois ce qu'il a pesé à la réception.</p>
 */
@ApplicationScoped
public class DispatchNoteService {

    @Inject DispatchNoteRepository repo;
    @Inject DispatchNoteRefService refService;
    @Inject ProducerPurchaseRepository receipts;
    @Inject CustomerRepository customers;
    @Inject SiteRepository sites;
    @Inject CampaignResolver campaignResolver;
    @Inject StockService stockService;
    @Inject StockItemRepository stockItems;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    // ─── Lecture ────────────────────────────────────────────────────

    public Pagination<DispatchNoteResponseDto> page(DispatchNoteStatus status, UUID siteId,
                                                    PageRequest pr) {
        long total = repo.count(status, siteId);
        List<DispatchNoteResponseDto> items = repo.list(status, siteId, pr.skip(), pr.perPage())
                .stream().map(DispatchNoteResponseDto::from).toList();
        return Pagination.of(total, pr, new String[]{"date"}, "desc",
                new java.util.HashMap<>(), items);
    }

    public DispatchNoteResponseDto getById(UUID id) {
        return DispatchNoteResponseDto.from(loadOrFail(id));
    }

    public DispatchNoteEntity loadOrFail(UUID id) {
        return repo.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.dsp-note-not-found", id)));
    }

    // ─── Création ───────────────────────────────────────────────────

    public DispatchNoteResponseDto create(CreateDispatchNoteDto p) {
        if (p.siteId() == null) {
            throw new BusinessException(Messages.msg("m.stk-site-required"));
        }

        // Les reçus appelés, tous du même article et du même site : un
        // camion charge une matière, pas un assortiment.
        List<ProducerPurchaseEntity> called = new ArrayList<>();
        for (DispatchLineInputDto line : p.lines()) {
            ProducerPurchaseEntity receipt = receipts.findById(line.receiptId()).orElseThrow(
                    () -> new NotFoundException(Messages.msg("m.ppu-receipt-not-found", line.receiptId())));
            if (receipt.isCancelled()) {
                throw new BusinessException(Messages.msg("m.dsp-receipt-cancelled", receipt.ref));
            }
            if (!p.siteId().equals(receipt.siteId)) {
                throw new BusinessException(Messages.msg("m.dsp-receipt-other-site", receipt.ref));
            }
            called.add(receipt);
        }
        UUID articleId = called.get(0).articleId;
        for (ProducerPurchaseEntity receipt : called) {
            if (!articleId.equals(receipt.articleId)) {
                throw new BusinessException(Messages.msg("m.dsp-mixed-articles",
                        called.get(0).articleName, receipt.articleName));
            }
        }

        // Appels atomiques sur le disponible de chaque reçu, avec retour
        // arrière complet si l'un d'eux ne peut pas suivre.
        List<DispatchLineInputDto> consumed = new ArrayList<>();
        for (int i = 0; i < p.lines().size(); i++) {
            DispatchLineInputDto line = p.lines().get(i);
            if (!receipts.tryDispatch(line.receiptId(), line.netKg())) {
                for (DispatchLineInputDto done : consumed) {
                    receipts.releaseDispatch(done.receiptId(), done.netKg());
                }
                ProducerPurchaseEntity receipt = called.get(i);
                BigDecimal available = nz(receipt.weightKg).subtract(nz(receipt.dispatchedKg));
                throw new BusinessException(Messages.msg("m.dsp-receipt-exhausted",
                        receipt.ref, line.netKg(), available));
            }
            consumed.add(line);
        }

        Instant now = Instant.now();
        DispatchNoteEntity e = new DispatchNoteEntity();
        try {
            e.id = idGenerator.newId();
            e.ref = refService.next();
            e.date = p.date();
            e.siteId = p.siteId();
            e.siteName = sites.findById(p.siteId()).map(s -> s.name).orElse(null);
            ProducerPurchaseEntity first = called.get(0);
            e.articleId = first.articleId;
            e.articleCode = first.articleCode;
            e.articleName = first.articleName;
            e.articleUnit = first.articleUnit;
            if (p.customerId() != null) {
                customers.findById(p.customerId()).ifPresent(c -> {
                    e.customerId = c.id;
                    e.customerName = c.name;
                });
            }
            e.truckNumber = blankToNull(p.truckNumber());
            CampaignEntity campaign = campaignResolver.resolveOptionalForInstant(now, null);
            e.campaignId = campaign != null ? campaign.id : null;
            e.campaignYear = campaign != null ? campaign.campaignYear : null;
            e.notes = blankToNull(p.notes());

            BigDecimal cmup = stockItems.findByArticleAndSite(e.articleId, e.siteId)
                    .map(it -> it.cmup).orElse(BigDecimal.ZERO);

            List<DispatchLine> lines = new ArrayList<>(p.lines().size());
            BigDecimal totalGross = BigDecimal.ZERO;
            int totalBags = 0;
            BigDecimal totalNet = BigDecimal.ZERO;
            for (int i = 0; i < p.lines().size(); i++) {
                DispatchLineInputDto input = p.lines().get(i);
                ProducerPurchaseEntity receipt = called.get(i);
                DispatchLine line = new DispatchLine();
                line.receiptId = receipt.id;
                line.receiptRef = receipt.ref;
                line.lotRef = receipt.deliveryRef != null ? receipt.deliveryRef : receipt.ref;
                line.netKg = input.netKg();
                line.bagsCount = input.bagsCount();
                line.grossKg = input.bagsCount() != null
                        ? input.netKg().add(BigDecimal.valueOf(input.bagsCount()))
                        : input.netKg();
                line.cmupAtDispatch = cmup;
                lines.add(line);
                totalGross = totalGross.add(line.grossKg);
                totalBags += input.bagsCount() != null ? input.bagsCount() : 0;
                totalNet = totalNet.add(input.netKg());
            }
            e.lines = lines;
            e.totalGrossKg = totalGross;
            e.totalBags = totalBags;
            e.totalNetKg = totalNet;
            e.createdAt = now;
            e.updatedAt = now;
            e.createdByEmail = actor();
            repo.insert(e);

            // Une sortie de stock par ligne, chacune sous le lot de son
            // reçu : la traçabilité aval y lira l'exact.
            for (DispatchLine line : lines) {
                stockService.applyMovement(new MovementInput(
                        e.articleId, e.siteId, MovementKind.OUT, line.netKg, cmup,
                        MovementSource.DISPATCH_NOTE, e.ref, e.id, null,
                        "Chargement " + e.ref + (e.customerName != null ? " vers " + e.customerName : ""),
                        null,
                        p.date().atStartOfDay(ZoneOffset.UTC).toInstant(),
                        false, line.lotRef, false));
            }
        } catch (RuntimeException ex) {
            for (DispatchLineInputDto done : consumed) {
                receipts.releaseDispatch(done.receiptId(), done.netKg());
            }
            throw ex;
        }

        audit.event(AuditEventType.STOCK_MOVEMENT_RECORDED)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Bordereau de sortie " + e.ref + " : " + e.totalNetKg + " kg "
                        + e.articleName + " en " + e.lines.size() + " appel(s)"
                        + (e.customerName != null ? " vers " + e.customerName : ""))
                .record();
        return DispatchNoteResponseDto.from(e);
    }

    // ─── Annulation ─────────────────────────────────────────────────

    public DispatchNoteResponseDto cancel(UUID id, String reason) {
        DispatchNoteEntity e = loadOrFail(id);
        if (e.status == DispatchNoteStatus.CANCELLED) {
            throw new BusinessException(Messages.msg("m.dsp-already-cancelled", e.ref));
        }
        if (e.status == DispatchNoteStatus.SOLD) {
            throw new BusinessException(Messages.msg("m.dsp-cancel-sold", e.ref, e.saleRef));
        }

        // Le stock revient ligne à ligne, chaque reçu retrouve son reliquat.
        for (DispatchLine line : e.lines) {
            stockService.applyMovement(new MovementInput(
                    e.articleId, e.siteId, MovementKind.IN, line.netKg, line.cmupAtDispatch,
                    MovementSource.DISPATCH_NOTE, e.ref, e.id, null,
                    "Contre-passation " + e.ref, null,
                    Instant.now(), true, line.lotRef, false));
            receipts.releaseDispatch(line.receiptId, line.netKg);
        }

        e.status = DispatchNoteStatus.CANCELLED;
        e.cancellationReason = reason == null ? "" : reason.trim();
        e.cancelledAt = Instant.now();
        e.cancelledByEmail = actor();
        e.updatedAt = e.cancelledAt;
        repo.replace(e);

        audit.event(AuditEventType.STOCK_MOVEMENT_RECORDED)
                .actorEmail(actor())
                .target("dispatch_note", e.id.toString(), e.ref)
                .tenant(tenantContext.tenantId(), null)
                .description("Contre-passation du bordereau de sortie " + e.ref + " : "
                        + e.cancellationReason)
                .record();
        return DispatchNoteResponseDto.from(e);
    }

    // ─── Internals ──────────────────────────────────────────────────

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
