package com.ntech.cabosse.catalog.controller;

import com.ntech.cabosse.catalog.dto.CityAdminDto;
import com.ntech.cabosse.catalog.dto.CityUpsertDto;
import com.ntech.cabosse.catalog.dto.CountryAdminDto;
import com.ntech.cabosse.catalog.dto.CountryUpsertDto;
import com.ntech.cabosse.catalog.dto.IndustryAdminDto;
import com.ntech.cabosse.catalog.dto.IndustryUpsertDto;
import com.ntech.cabosse.catalog.dto.PlanAdminDto;
import com.ntech.cabosse.catalog.dto.PlanUpsertDto;
import com.ntech.cabosse.catalog.dto.RegionAdminDto;
import com.ntech.cabosse.catalog.dto.RegionUpsertDto;
import com.ntech.cabosse.catalog.entity.CityEntity;
import com.ntech.cabosse.catalog.entity.CountryEntity;
import com.ntech.cabosse.catalog.entity.IndustryEntity;
import com.ntech.cabosse.catalog.entity.RegionEntity;
import com.ntech.cabosse.catalog.repository.CityRepository;
import com.ntech.cabosse.catalog.repository.CountryRepository;
import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.catalog.repository.RegionRepository;
import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.api.ApiResponse;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Administration des catalogues plateforme. Réservé aux super-admins.
 *
 * <p>Pour chacune des 5 entités catalogue :
 * <ul>
 *   <li>{@code GET /admin/catalog/<entité>} — liste complète (inactifs inclus)</li>
 *   <li>{@code POST /admin/catalog/<entité>} — création</li>
 *   <li>{@code PUT /admin/catalog/<entité>/{id}} — mise à jour</li>
 *   <li>{@code PATCH /admin/catalog/<entité>/{id}/active?value=true|false} — toggle activation</li>
 * </ul>
 *
 * <p>Pas de DELETE — la suppression dure casse les références côté
 * tenants (un tenant peut référencer un plan retiré). La désactivation
 * suffit : les entrées inactives disparaissent du dropdown front mais
 * restent dans la base pour la cohérence historique.</p>
 */
@Path("/api/v1/admin/catalog")
@Tag(name = "Admin · Catalogues", description = "CRUD des référentiels plateforme")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.PLATFORM_ADMIN)
public class CatalogAdminResource {

    @Inject CountryRepository countries;
    @Inject RegionRepository regions;
    @Inject CityRepository cities;
    @Inject IndustryRepository industries;
    @Inject PlanRepository plans;
    @Inject IdGenerator idGenerator;
    @Inject com.ntech.cabosse.shared.audit.AuditService audit;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;

    private String currentAdmin() {
        try { return jwt.getName() != null ? jwt.getName() : "unknown@platform"; }
        catch (Exception e) { return "unknown@platform"; }
    }

    private void auditCatalog(String targetType, String id, String label, String action) {
        audit.event(com.ntech.cabosse.shared.audit.AuditEventType.CATALOG_UPDATED)
                .actorEmail(currentAdmin())
                .target(targetType, id, label)
                .description(action + " · " + targetType + " « " + label + " »")
                .payload(java.util.Map.of("action", action))
                .record();
    }

    // ─── Countries ───────────────────────────────────────────────────

    @GET
    @Path("/countries")
    public Response listCountries() {
        List<CountryAdminDto> body = countries.listAll().stream()
                .sorted(Comparator.comparing((CountryEntity c) -> c.nameFr))
                .map(c -> new CountryAdminDto(c.code, c.nameFr, c.nameEn, c.dialCode, c.isActive))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/countries")
    @Transactional
    public Response createCountry(@Valid CountryUpsertDto payload) {
        if (payload.code() == null || payload.code().isBlank()) {
            throw new BusinessException("Code ISO requis à la création.");
        }
        if (countries.codeExists(payload.code())) {
            throw new ConflictException("Le code " + payload.code() + " existe déjà.");
        }
        CountryEntity entity = new CountryEntity();
        entity.code = payload.code();
        applyCountry(entity, payload);
        countries.persist(entity);
        auditCatalog("country", entity.code, entity.nameFr, "Création");
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(toCountryDto(entity)))
                .build();
    }

    @PUT
    @Path("/countries/{code}")
    @Transactional
    public Response updateCountry(@PathParam("code") String code, @Valid CountryUpsertDto payload) {
        CountryEntity entity = countries.findById(code);
        if (entity == null) throw new NotFoundException("Pays " + code + " introuvable.");
        applyCountry(entity, payload);
        countries.update(entity);
        auditCatalog("country", entity.code, entity.nameFr, "Modification");
        return Response.ok(ApiResponse.ok(toCountryDto(entity))).build();
    }

    @PATCH
    @Path("/countries/{code}/active")
    @Transactional
    public Response toggleCountryActive(@PathParam("code") String code,
                                        @jakarta.ws.rs.QueryParam("value") boolean value) {
        CountryEntity entity = countries.findById(code);
        if (entity == null) throw new NotFoundException("Pays " + code + " introuvable.");
        entity.isActive = value;
        countries.update(entity);
        auditCatalog("country", entity.code, entity.nameFr, value ? "Réactivation" : "Désactivation");
        return Response.ok(ApiResponse.ok(toCountryDto(entity))).build();
    }

    private static void applyCountry(CountryEntity entity, CountryUpsertDto p) {
        entity.nameFr = p.nameFr().trim();
        entity.nameEn = p.nameEn() != null ? p.nameEn().trim() : null;
        entity.dialCode = p.dialCode() != null ? p.dialCode().trim() : null;
        entity.isActive = p.isActive();
    }

    private static CountryAdminDto toCountryDto(CountryEntity c) {
        return new CountryAdminDto(c.code, c.nameFr, c.nameEn, c.dialCode, c.isActive);
    }

    // ─── Regions ─────────────────────────────────────────────────────

    @GET
    @Path("/regions")
    public Response listRegions() {
        List<RegionAdminDto> body = regions.listAll().stream()
                .sorted(Comparator.comparing((RegionEntity r) -> r.countryCode)
                        .thenComparing(r -> r.name))
                .map(r -> new RegionAdminDto(r.code, r.name, r.countryCode, r.districtCode, r.isActive))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/regions")
    @Transactional
    public Response createRegion(@Valid RegionUpsertDto payload) {
        if (payload.code() == null || payload.code().isBlank()) {
            throw new BusinessException("Code requis à la création.");
        }
        if (regions.codeExists(payload.code())) {
            throw new ConflictException("Le code " + payload.code() + " existe déjà.");
        }
        if (!countries.codeExists(payload.countryCode())) {
            throw new BusinessException("Pays " + payload.countryCode() + " inconnu.");
        }
        RegionEntity entity = new RegionEntity();
        entity.code = payload.code();
        applyRegion(entity, payload);
        regions.persist(entity);
        auditCatalog("region", entity.code, entity.name, "Création");
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(toRegionDto(entity)))
                .build();
    }

    @PUT
    @Path("/regions/{code}")
    @Transactional
    public Response updateRegion(@PathParam("code") String code, @Valid RegionUpsertDto payload) {
        RegionEntity entity = regions.findById(code);
        if (entity == null) throw new NotFoundException("Région " + code + " introuvable.");
        if (!countries.codeExists(payload.countryCode())) {
            throw new BusinessException("Pays " + payload.countryCode() + " inconnu.");
        }
        applyRegion(entity, payload);
        regions.update(entity);
        auditCatalog("region", entity.code, entity.name, "Modification");
        return Response.ok(ApiResponse.ok(toRegionDto(entity))).build();
    }

    @PATCH
    @Path("/regions/{code}/active")
    @Transactional
    public Response toggleRegionActive(@PathParam("code") String code,
                                       @jakarta.ws.rs.QueryParam("value") boolean value) {
        RegionEntity entity = regions.findById(code);
        if (entity == null) throw new NotFoundException("Région " + code + " introuvable.");
        entity.isActive = value;
        regions.update(entity);
        auditCatalog("region", entity.code, entity.name, value ? "Réactivation" : "Désactivation");
        return Response.ok(ApiResponse.ok(toRegionDto(entity))).build();
    }

    private static void applyRegion(RegionEntity entity, RegionUpsertDto p) {
        entity.name = p.name().trim();
        entity.countryCode = p.countryCode();
        entity.districtCode = (p.districtCode() == null || p.districtCode().isBlank())
                ? null : p.districtCode().trim();
        entity.isActive = p.isActive();
    }

    private static RegionAdminDto toRegionDto(RegionEntity r) {
        return new RegionAdminDto(r.code, r.name, r.countryCode, r.districtCode, r.isActive);
    }

    // ─── Cities ──────────────────────────────────────────────────────

    @GET
    @Path("/cities")
    public Response listCities() {
        List<CityAdminDto> body = cities.listAll().stream()
                .sorted(Comparator.comparing((CityEntity c) -> c.countryCode)
                        .thenComparing(c -> c.regionCode)
                        .thenComparing(c -> c.name))
                .map(c -> new CityAdminDto(c.id, c.name, c.regionCode, c.countryCode, c.isActive))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/cities")
    @Transactional
    public Response createCity(@Valid CityUpsertDto payload) {
        if (!regions.codeExists(payload.regionCode())) {
            throw new BusinessException("Région " + payload.regionCode() + " inconnue.");
        }
        if (!countries.codeExists(payload.countryCode())) {
            throw new BusinessException("Pays " + payload.countryCode() + " inconnu.");
        }
        CityEntity entity = new CityEntity();
        entity.id = idGenerator.newId();
        applyCity(entity, payload);
        cities.persist(entity);
        auditCatalog("city", entity.id.toString(), entity.name, "Création");
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(toCityDto(entity)))
                .build();
    }

    @PUT
    @Path("/cities/{id}")
    @Transactional
    public Response updateCity(@PathParam("id") UUID id, @Valid CityUpsertDto payload) {
        CityEntity entity = cities.findById(id);
        if (entity == null) throw new NotFoundException("Ville " + id + " introuvable.");
        if (!regions.codeExists(payload.regionCode())) {
            throw new BusinessException("Région " + payload.regionCode() + " inconnue.");
        }
        applyCity(entity, payload);
        cities.update(entity);
        auditCatalog("city", entity.id.toString(), entity.name, "Modification");
        return Response.ok(ApiResponse.ok(toCityDto(entity))).build();
    }

    @PATCH
    @Path("/cities/{id}/active")
    @Transactional
    public Response toggleCityActive(@PathParam("id") UUID id,
                                     @jakarta.ws.rs.QueryParam("value") boolean value) {
        CityEntity entity = cities.findById(id);
        if (entity == null) throw new NotFoundException("Ville " + id + " introuvable.");
        entity.isActive = value;
        cities.update(entity);
        auditCatalog("city", entity.id.toString(), entity.name, value ? "Réactivation" : "Désactivation");
        return Response.ok(ApiResponse.ok(toCityDto(entity))).build();
    }

    private static void applyCity(CityEntity entity, CityUpsertDto p) {
        entity.name = p.name().trim();
        entity.regionCode = p.regionCode();
        entity.countryCode = p.countryCode();
        entity.isActive = p.isActive();
    }

    private static CityAdminDto toCityDto(CityEntity c) {
        return new CityAdminDto(c.id, c.name, c.regionCode, c.countryCode, c.isActive);
    }

    // ─── Industries ──────────────────────────────────────────────────

    @GET
    @Path("/industries")
    public Response listIndustries() {
        List<IndustryAdminDto> body = industries.listAll().stream()
                .sorted(Comparator.comparing((IndustryEntity i) -> i.label))
                .map(CatalogAdminResource::toIndustryDto)
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/industries")
    @Transactional
    public Response createIndustry(@Valid IndustryUpsertDto payload) {
        if (payload.code() == null || payload.code().isBlank()) {
            throw new BusinessException("Code requis à la création.");
        }
        if (industries.codeExists(payload.code())) {
            throw new ConflictException("Le code " + payload.code() + " existe déjà.");
        }
        IndustryEntity entity = new IndustryEntity();
        entity.code = payload.code();
        applyIndustry(entity, payload);
        industries.persist(entity);
        auditCatalog("industry", entity.code, entity.label, "Création");
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(toIndustryDto(entity)))
                .build();
    }

    @PUT
    @Path("/industries/{code}")
    @Transactional
    public Response updateIndustry(@PathParam("code") String code, @Valid IndustryUpsertDto payload) {
        IndustryEntity entity = industries.findById(code);
        if (entity == null) throw new NotFoundException("Activité " + code + " introuvable.");
        applyIndustry(entity, payload);
        industries.update(entity);
        auditCatalog("industry", entity.code, entity.label, "Modification");
        return Response.ok(ApiResponse.ok(toIndustryDto(entity))).build();
    }

    @PATCH
    @Path("/industries/{code}/active")
    @Transactional
    public Response toggleIndustryActive(@PathParam("code") String code,
                                         @jakarta.ws.rs.QueryParam("value") boolean value) {
        IndustryEntity entity = industries.findById(code);
        if (entity == null) throw new NotFoundException("Activité " + code + " introuvable.");
        entity.isActive = value;
        industries.update(entity);
        auditCatalog("industry", entity.code, entity.label, value ? "Réactivation" : "Désactivation");
        return Response.ok(ApiResponse.ok(toIndustryDto(entity))).build();
    }

    private static void applyIndustry(IndustryEntity entity, IndustryUpsertDto p) {
        entity.label = p.label().trim();
        entity.description = p.description() != null ? p.description().trim() : null;
        entity.isActive = p.isActive();
        // Validation : chaque code activates[] doit être une valeur valide de
        // TenantCapability ; sinon le calcul de capacités l'ignore mais on
        // refuse à la saisie pour éviter les coquilles.
        if (p.activates() != null) {
            List<String> normalized = new java.util.ArrayList<>();
            for (String capName : p.activates()) {
                if (capName == null || capName.isBlank()) continue;
                String trimmed = capName.trim();
                try {
                    com.ntech.cabosse.tenant.capability.TenantCapability.valueOf(trimmed);
                } catch (IllegalArgumentException ex) {
                    throw new BusinessException("Capacité inconnue : " + trimmed);
                }
                if (!normalized.contains(trimmed)) normalized.add(trimmed);
            }
            entity.activates = normalized;
        } else {
            entity.activates = new java.util.ArrayList<>();
        }
    }

    private static IndustryAdminDto toIndustryDto(IndustryEntity i) {
        return new IndustryAdminDto(
                i.code, i.label, i.description, i.isActive,
                i.activates != null ? List.copyOf(i.activates) : List.of()
        );
    }

    // ─── Plans ───────────────────────────────────────────────────────

    @GET
    @Path("/plans")
    public Response listPlans() {
        List<PlanAdminDto> body = plans.listAll().stream()
                .sorted(Comparator.comparing((PlanEntity p) -> p.monthlyPriceFcfa))
                .map(p -> new PlanAdminDto(
                        p.code, p.name, p.description,
                        p.monthlyPriceFcfa, p.yearlyPriceFcfa,
                        p.maxUsers, p.maxSites,
                        p.includedModules, p.features,
                        p.active
                ))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @POST
    @Path("/plans")
    @Transactional
    public Response createPlan(@Valid PlanUpsertDto payload) {
        if (payload.code() == null || payload.code().isBlank()) {
            throw new BusinessException("Code requis à la création.");
        }
        if (plans.findByCode(payload.code()).isPresent()) {
            throw new ConflictException("Le code " + payload.code() + " existe déjà.");
        }
        PlanEntity entity = new PlanEntity();
        entity.code = payload.code();
        applyPlan(entity, payload);
        plans.persist(entity);
        auditCatalog("plan", entity.code, entity.name, "Création");
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponse.created(toPlanDto(entity)))
                .build();
    }

    @PUT
    @Path("/plans/{code}")
    @Transactional
    public Response updatePlan(@PathParam("code") String code, @Valid PlanUpsertDto payload) {
        PlanEntity entity = plans.findByCode(code).orElse(null);
        if (entity == null) throw new NotFoundException("Plan " + code + " introuvable.");
        applyPlan(entity, payload);
        plans.update(entity);
        auditCatalog("plan", entity.code, entity.name, "Modification");
        return Response.ok(ApiResponse.ok(toPlanDto(entity))).build();
    }

    @PATCH
    @Path("/plans/{code}/active")
    @Transactional
    public Response togglePlanActive(@PathParam("code") String code,
                                     @jakarta.ws.rs.QueryParam("value") boolean value) {
        PlanEntity entity = plans.findByCode(code).orElse(null);
        if (entity == null) throw new NotFoundException("Plan " + code + " introuvable.");
        entity.active = value;
        plans.update(entity);
        auditCatalog("plan", entity.code, entity.name, value ? "Réactivation" : "Désactivation");
        return Response.ok(ApiResponse.ok(toPlanDto(entity))).build();
    }

    private static void applyPlan(PlanEntity entity, PlanUpsertDto p) {
        entity.name = p.name().trim();
        entity.description = p.description() != null ? p.description().trim() : null;
        entity.monthlyPriceFcfa = p.monthlyPriceFcfa();
        entity.yearlyPriceFcfa = p.yearlyPriceFcfa();
        entity.maxUsers = p.maxUsers();
        entity.maxSites = p.maxSites();
        entity.includedModules = p.includedModules();
        entity.features = p.features();
        entity.active = p.active();
    }

    private static PlanAdminDto toPlanDto(PlanEntity p) {
        return new PlanAdminDto(
                p.code, p.name, p.description,
                p.monthlyPriceFcfa, p.yearlyPriceFcfa,
                p.maxUsers, p.maxSites,
                p.includedModules, p.features,
                p.active
        );
    }
}
