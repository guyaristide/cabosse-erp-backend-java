package com.ntech.cabosse.tenant.service;

import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.dto.TenantDetailResponseDto;
import com.ntech.cabosse.tenant.dto.TenantSummaryResponseDto;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.mapper.TenantMapper;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.repository.UserRepository;
import io.quarkus.mongodb.panache.PanacheQuery;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service de lecture du registre des tenants. Phase B : list paginée + detail.
 *
 * <p>Liste paginée avec filtres optionnels par statut technique et par plan.
 * Pagination conforme à la spec API (cf. CLAUDE.md §10.3, §10.4).</p>
 */
@ApplicationScoped
public class TenantRegistryService {

    public static final int DEFAULT_PER_PAGE = 20;
    public static final int MAX_PER_PAGE = 100;

    @Inject TenantRepository tenants;
    @Inject UserRepository users;

    /**
     * Liste paginée des tenants. Tri par défaut : {@code createdAt} desc.
     *
     * @param page    index 0-based
     * @param perPage taille de page demandée, bornée à {@link #MAX_PER_PAGE}
     * @param status  filtre optionnel par statut technique
     * @param plan    filtre optionnel par code de plan
     */
    public Pagination<TenantSummaryResponseDto> list(int page, int perPage,
                                                     TenantStatus status, String plan) {
        int safePerPage = clampPerPage(perPage);
        int safePage = Math.max(0, page);

        Map<String, String> filters = new HashMap<>();
        Map<String, Object> mongoFilters = new HashMap<>();
        if (status != null) {
            mongoFilters.put("status", status);
            filters.put("status", status.name());
        }
        if (plan != null && !plan.isBlank()) {
            mongoFilters.put("planCode", plan);
            filters.put("plan", plan);
        }

        Sort sort = Sort.descending("createdAt");
        PanacheQuery<TenantEntity> query = mongoFilters.isEmpty()
                ? tenants.findAll(sort)
                : tenants.find(toQueryString(mongoFilters), sort, mongoFilters);

        List<TenantEntity> entities = query.page(safePage, safePerPage).list();

        long total = mongoFilters.isEmpty()
                ? tenants.count()
                : tenants.count(toQueryString(mongoFilters), mongoFilters);
        int totalOfPages = (int) Math.ceil((double) total / safePerPage);

        List<TenantSummaryResponseDto> items = entities.stream()
                .map(entity -> TenantMapper.toSummary(entity, users.countByTenant(entity.id)))
                .toList();

        return new Pagination<>(
                total,
                totalOfPages,
                (long) safePerPage,
                new String[]{"createdAt"},
                "desc",
                safePage,
                Math.min(safePage + 1, Math.max(0, totalOfPages - 1)),
                Math.max(0, safePage - 1),
                filters,
                items
        );
    }

    public TenantDetailResponseDto getById(UUID tenantId) {
        TenantEntity entity = tenants.findById(tenantId);
        if (entity == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }
        long usersCount = users.countByTenant(entity.id);
        return TenantMapper.toDetail(entity, usersCount);
    }

    // ─── Internals ───

    private static int clampPerPage(int requested) {
        if (requested <= 0) return DEFAULT_PER_PAGE;
        return Math.min(requested, MAX_PER_PAGE);
    }

    /** Construit un fragment Panache "status = :status and planCode = :plan" à partir de la map. */
    private static String toQueryString(Map<String, Object> filters) {
        if (filters.isEmpty()) return "";
        return filters.keySet().stream()
                .map(k -> k + " = :" + k)
                .reduce((a, b) -> a + " and " + b)
                .orElse("");
    }
}
