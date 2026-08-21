package com.ntech.cabosse.catalog.controller;

import com.ntech.cabosse.catalog.dto.CityResponseDto;
import com.ntech.cabosse.catalog.dto.CountryResponseDto;
import com.ntech.cabosse.catalog.dto.IndustryResponseDto;
import com.ntech.cabosse.catalog.dto.PlanResponseDto;
import com.ntech.cabosse.catalog.dto.RegionResponseDto;
import com.ntech.cabosse.catalog.repository.CityRepository;
import com.ntech.cabosse.catalog.repository.CountryRepository;
import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.catalog.repository.RegionRepository;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.api.ApiResponse;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Lecture seule des catalogues partagés (pays, régions, villes, activités,
 * plans). Consommé par le front pour peupler les dropdowns du formulaire
 * de provisioning et les écrans d'admin du tenant.
 *
 * <p>Accessible à tout utilisateur authentifié. L'édition de ces
 * catalogues (réservée à {@code PLATFORM_ADMIN}) sera ajoutée en Phase
 * C/D via un autre resource sous {@code /api/v1/admin/catalog/*}.</p>
 */
@Path("/api/v1/catalog")
@Tag(name = "Catalog", description = "Référentiels partagés en lecture")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class CatalogResource {

    @Inject CountryRepository countries;
    @Inject RegionRepository regions;
    @Inject CityRepository cities;
    @Inject IndustryRepository industries;
    @Inject PlanRepository plans;

    @GET
    @Path("/countries")
    @Operation(summary = "Liste des pays actifs",
            description = "Tous les pays disponibles dans les formulaires, triés par nom français.")
    public Response listCountries() {
        List<CountryResponseDto> body = countries.findAllActive().stream()
                .map(e -> new CountryResponseDto(e.code, e.nameFr, e.nameEn, e.dialCode))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @GET
    @Path("/countries/{countryCode}/regions")
    @Operation(summary = "Régions d'un pays",
            description = "Subdivisions administratives utilisées dans l'adresse d'un tenant.")
    public Response listRegions(@PathParam("countryCode") String countryCode) {
        List<RegionResponseDto> body = regions.findByCountry(countryCode).stream()
                .map(e -> new RegionResponseDto(e.code, e.name, e.countryCode, e.districtCode))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @GET
    @Path("/regions/{regionCode}/cities")
    @Operation(summary = "Villes d'une région",
            description = "Villes référencées dans une région donnée, triées par nom.")
    public Response listCities(@PathParam("regionCode") String regionCode) {
        List<CityResponseDto> body = cities.findByRegion(regionCode).stream()
                .map(e -> new CityResponseDto(e.id, e.name, e.regionCode, e.countryCode))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @GET
    @Path("/industries")
    @Operation(summary = "Catalogue des activités / filières",
            description = "Liste des activités déclarables par un tenant. Strict : la création "
                    + "d'un tenant n'accepte que des codes présents ici.")
    public Response listIndustries() {
        List<IndustryResponseDto> body = industries.findAllActive().stream()
                .map(e -> new IndustryResponseDto(e.code, e.label, e.description))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }

    @GET
    @Path("/plans")
    @Operation(summary = "Plans tarifaires actifs",
            description = "Plans disponibles à la souscription. Les plans inactifs (historiques) "
                    + "sont conservés mais non listés ici.")
    public Response listPlans() {
        List<PlanResponseDto> body = plans.findActivePlans().stream()
                .map(e -> new PlanResponseDto(
                        e.code, e.name, e.description,
                        e.monthlyPriceFcfa, e.yearlyPriceFcfa,
                        e.maxUsers, e.maxMembers, e.maxSites,
                        e.includedModules, e.features
                ))
                .toList();
        return Response.ok(ApiResponse.ok(body)).build();
    }
}
