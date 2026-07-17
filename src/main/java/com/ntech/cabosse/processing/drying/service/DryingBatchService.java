package com.ntech.cabosse.processing.drying.service;

import com.ntech.cabosse.processing.drying.dto.DryingBatchResponseDto;
import com.ntech.cabosse.processing.drying.dto.DryingBatchUpsertDto;
import com.ntech.cabosse.processing.drying.entity.DryingBatchEntity;
import com.ntech.cabosse.processing.drying.entity.DryingBatchStatus;
import com.ntech.cabosse.processing.drying.repository.DryingBatchRepository;
import com.ntech.cabosse.processing.fermentation.entity.FermentationBatchEntity;
import com.ntech.cabosse.processing.fermentation.repository.FermentationBatchRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DryingBatchService {

    @Inject DryingBatchRepository batches;
    @Inject DryingRefService refService;
    @Inject FermentationBatchRepository fermentations;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private UUID safeUserId() {
        try { return tenantContext.userId(); } catch (Exception e) { return null; }
    }

    public Pagination<DryingBatchResponseDto> page(DryingBatchStatus statusFilter, String q, PageRequest pr) {
        long total = batches.countSearch(statusFilter, q);
        List<DryingBatchResponseDto> items =
                batches.search(statusFilter, q, pr.skip(), pr.perPage()).stream()
                        .map(DryingBatchResponseDto::from)
                        .toList();
        Map<String, String> filters = new HashMap<>();
        if (statusFilter != null) filters.put("status", statusFilter.name());
        if (q != null && !q.isBlank()) filters.put("q", q.trim());
        return Pagination.of(total, pr, new String[]{"startedAt", "createdAt"}, "desc",
                filters, items);
    }

    public DryingBatchResponseDto getById(UUID id) {
        return DryingBatchResponseDto.from(loadOrFail(id));
    }

    public DryingBatchResponseDto create(DryingBatchUpsertDto payload) {
        List<FermentationBatchEntity> ferms = fermentations.findByIds(payload.fermentationBatchIds());
        if (ferms.size() != payload.fermentationBatchIds().size()) {
            throw new BusinessException("Certains bacs fermentation référencés sont introuvables.");
        }
        DryingBatchEntity e = new DryingBatchEntity();
        e.id = idGenerator.newId();
        e.ref = refService.next();
        e.fermentationBatchIds = new ArrayList<>(payload.fermentationBatchIds());
        e.fermentationBatchRefs = ferms.stream().map(f -> f.ref).toList();
        e.method = payload.method();
        e.weightInKg = payload.weightInKg();
        e.status = DryingBatchStatus.DRYING;
        e.startedAt = Instant.now();
        e.notes = blankToNull(payload.notes());
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        e.createdBy = safeUserId();
        e.createdByEmail = actor();
        batches.insert(e);
        return DryingBatchResponseDto.from(e);
    }

    public DryingBatchResponseDto complete(UUID id, BigDecimal weightOutKg, BigDecimal finalHumidityPct) {
        DryingBatchEntity e = loadOrFail(id);
        if (e.status != DryingBatchStatus.DRYING) {
            throw new BusinessException("Séchage non actif — clôture impossible.");
        }
        e.weightOutKg = weightOutKg;
        e.finalHumidityPct = finalHumidityPct;
        if (e.weightInKg != null && e.weightInKg.signum() > 0 && weightOutKg != null) {
            e.weightLossPct = e.weightInKg.subtract(weightOutKg)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(e.weightInKg, 2, RoundingMode.HALF_UP);
        }
        e.completedAt = Instant.now();
        if (e.startedAt != null) {
            e.durationHours = (int) Duration.between(e.startedAt, e.completedAt).toHours();
        }
        e.status = DryingBatchStatus.COMPLETED;
        e.updatedAt = e.completedAt;
        batches.replace(e);
        return DryingBatchResponseDto.from(e);
    }

    public DryingBatchResponseDto cancel(UUID id, String reason) {
        DryingBatchEntity e = loadOrFail(id);
        if (e.status == DryingBatchStatus.COMPLETED) {
            throw new BusinessException("Séchage clôturé — annulation impossible.");
        }
        e.status = DryingBatchStatus.CANCELLED;
        e.notes = (e.notes != null ? e.notes + "\n" : "") + "Annulé : " + (reason != null ? reason : "");
        e.updatedAt = Instant.now();
        batches.replace(e);
        return DryingBatchResponseDto.from(e);
    }

    private DryingBatchEntity loadOrFail(UUID id) {
        return batches.findById(id)
                .orElseThrow(() -> new NotFoundException("Séchage " + id + " introuvable."));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
