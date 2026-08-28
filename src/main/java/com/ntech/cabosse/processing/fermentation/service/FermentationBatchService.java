package com.ntech.cabosse.processing.fermentation.service;

import com.ntech.cabosse.agriculture.harvest.entity.HarvestEntity;
import com.ntech.cabosse.agriculture.harvest.repository.HarvestRepository;
import com.ntech.cabosse.processing.fermentation.dto.FermentationBatchResponseDto;
import com.ntech.cabosse.processing.fermentation.dto.FermentationBatchUpsertDto;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchEntity;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchStatus;
import com.ntech.cabosse.processing.fermentation.entity.TemperatureReading;
import com.ntech.cabosse.processing.fermentation.entity.Turning;
import com.ntech.cabosse.processing.fermentation.repository.FermentationBatchRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des bacs de fermentation : création (PREPARING) → start
 * (ACTIVE) → readings/turnings en continu → complete (COMPLETED).
 *
 * <p>Une fois un bac COMPLETED, ses fèves sont transférées vers un
 * batch de séchage. La traçabilité parcelles → bac est conservée via
 * {@code harvestIds} (chaque récolte étant elle-même rattachée à une
 * parcelle).</p>
 */
@ApplicationScoped
public class FermentationBatchService {

    @Inject FermentationBatchRepository batches;
    @Inject FermentationRefService refService;
    @Inject HarvestRepository harvests;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public Pagination<FermentationBatchResponseDto> page(FermentationBatchStatus statusFilter, String q, PageRequest pr) {
        long total = batches.countSearch(statusFilter, q);
        List<FermentationBatchResponseDto> items =
                batches.search(statusFilter, q, pr.skip(), pr.perPage()).stream()
                        .map(FermentationBatchResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (statusFilter != null) filters.put("status", statusFilter.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"startedAt", "createdAt"}, "desc",
                filters, items);
    }

    public FermentationBatchResponseDto getById(UUID id) {
        return FermentationBatchResponseDto.from(loadOrFail(id));
    }

    /**
     * Ouvre un bac de fermentation.
     *
     * <p>Les récoltes sont facultatives : une structure qui achète sa
     * matière au lieu de la récolter charge un bac sans récolte à lui
     * rattacher. Quand des récoltes sont données, elles doivent toutes
     * exister.</p>
     */
    public FermentationBatchResponseDto create(FermentationBatchUpsertDto payload) {
        List<UUID> requested = payload.harvestIds() != null ? payload.harvestIds() : List.of();
        List<HarvestEntity> hList = requested.isEmpty() ? List.of() : harvests.findByIds(requested);
        if (hList.size() != requested.size()) {
            throw new BusinessException(Messages.msg("m.prc-harvests-not-found"));
        }
        FermentationBatchEntity e = new FermentationBatchEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.harvestIds = new ArrayList<>(requested);
        e.harvestCodes = hList.stream().map(h -> h.code).toList();
        e.weightInKg = payload.weightInKg();
        e.status = FermentationBatchStatus.PREPARING;
        e.notes = blankToNull(payload.notes());
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        batches.insert(e);
        return FermentationBatchResponseDto.from(e);
    }

    public FermentationBatchResponseDto start(UUID id) {
        FermentationBatchEntity e = loadOrFail(id);
        if (e.status != FermentationBatchStatus.PREPARING) {
            throw new BusinessException(Messages.msg("m.prc-start-refused", e.status));
        }
        e.status = FermentationBatchStatus.ACTIVE;
        e.startedAt = Instant.now();
        e.updatedAt = e.startedAt;
        batches.replace(e);
        return FermentationBatchResponseDto.from(e);
    }

    public FermentationBatchResponseDto recordTemperature(UUID id, BigDecimal celsius, String observation) {
        FermentationBatchEntity e = loadOrFail(id);
        if (e.status != FermentationBatchStatus.ACTIVE) {
            throw new BusinessException(Messages.msg("m.prc-batch-not-active-temperature"));
        }
        TemperatureReading r = new TemperatureReading();
        r.at = Instant.now();
        r.celsius = celsius;
        r.observation = blankToNull(observation);
        r.recordedByEmail = actor();
        // $push atomique conditionné ACTIVE : deux relevés simultanés de
        // deux opérateurs terrain ne s'écrasent plus.
        if (!batches.pushIfActive(e.id, "temperatureReadings", r, r.at)) {
            throw new BusinessException(Messages.msg("m.prc-batch-not-active-temperature"));
        }
        return FermentationBatchResponseDto.from(loadOrFail(id));
    }

    public FermentationBatchResponseDto recordTurning(UUID id, String operator, String notes) {
        FermentationBatchEntity e = loadOrFail(id);
        if (e.status != FermentationBatchStatus.ACTIVE) {
            throw new BusinessException(Messages.msg("m.prc-batch-not-active-turning"));
        }
        Turning t = new Turning();
        t.at = Instant.now();
        t.operator = blankToNull(operator);
        t.notes = blankToNull(notes);
        if (!batches.pushIfActive(e.id, "turnings", t, t.at)) {
            throw new BusinessException(Messages.msg("m.prc-batch-not-active-turning"));
        }
        return FermentationBatchResponseDto.from(loadOrFail(id));
    }

    public FermentationBatchResponseDto complete(UUID id, BigDecimal weightOutKg, String finalGradeEstimate) {
        FermentationBatchEntity e = loadOrFail(id);
        if (e.status != FermentationBatchStatus.ACTIVE) {
            throw new BusinessException(Messages.msg("m.prc-batch-not-active-complete"));
        }
        e.weightOutKg = weightOutKg;
        e.finalGradeEstimate = blankToNull(finalGradeEstimate);
        e.completedAt = Instant.now();
        e.status = FermentationBatchStatus.COMPLETED;
        e.updatedAt = e.completedAt;
        batches.replace(e);
        return FermentationBatchResponseDto.from(e);
    }

    public FermentationBatchResponseDto cancel(UUID id, String reason) {
        FermentationBatchEntity e = loadOrFail(id);
        if (e.status == FermentationBatchStatus.COMPLETED) {
            throw new BusinessException(Messages.msg("m.prc-batch-completed-cancel"));
        }
        e.status = FermentationBatchStatus.CANCELLED;
        e.notes = (e.notes != null ? e.notes + "\n" : "") + "Annulé : " + (reason != null ? reason : "");
        e.updatedAt = Instant.now();
        batches.replace(e);
        return FermentationBatchResponseDto.from(e);
    }

    private FermentationBatchEntity loadOrFail(UUID id) {
        return batches.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.prc-batch-not-found", id)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
