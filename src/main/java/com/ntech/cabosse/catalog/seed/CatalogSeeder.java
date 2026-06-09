package com.ntech.cabosse.catalog.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntech.cabosse.catalog.entity.CityEntity;
import com.ntech.cabosse.catalog.entity.CountryEntity;
import com.ntech.cabosse.catalog.entity.CurrencyEntity;
import com.ntech.cabosse.catalog.entity.IndustryEntity;
import com.ntech.cabosse.catalog.entity.RegionEntity;
import com.ntech.cabosse.catalog.repository.CityRepository;
import com.ntech.cabosse.catalog.repository.CountryRepository;
import com.ntech.cabosse.catalog.repository.CurrencyRepository;
import com.ntech.cabosse.catalog.repository.IndustryRepository;
import com.ntech.cabosse.catalog.repository.RegionRepository;
import com.ntech.cabosse.plan.entity.PlanEntity;
import com.ntech.cabosse.plan.repository.PlanRepository;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Charge les catalogues (pays, régions, villes, activités, plans) depuis
 * les fichiers JSON {@code resources/catalog/*.json} et les persiste dans
 * le plan contrôle.
 *
 * <p>Idempotent au niveau ligne : chaque entrée est insérée seulement si
 * sa clé naturelle n'existe pas déjà. Ça permet d'enrichir les JSON au
 * fil de l'eau et de relancer la seed sans tout dropper. Pour les villes
 * dont le {@code _id} est un UUID généré, la clé naturelle est
 * {@code (regionCode, name)}.</p>
 *
 * <p>Appelé au démarrage par {@link CatalogBootstrap} en dev/prod, et
 * manuellement par {@code AbstractIntegrationTest.resetState()} en test
 * pour ré-amorcer après chaque drop de DB.</p>
 */
@ApplicationScoped
public class CatalogSeeder {

    @Inject CountryRepository countries;
    @Inject RegionRepository regions;
    @Inject CityRepository cities;
    @Inject IndustryRepository industries;
    @Inject CurrencyRepository currencies;
    @Inject PlanRepository plans;
    @Inject IdGenerator idGenerator;
    @Inject ObjectMapper objectMapper;
    @Inject Logger log;

    @Transactional
    public void seedAll() {
        int c = seedCountries();
        int r = seedRegions();
        int v = seedCities();
        int i = seedIndustries();
        int p = seedPlans();
        int cur = seedCurrencies();
        log.infof("Catalog seed — countries:%d, regions:%d, cities:%d, industries:%d, plans:%d, currencies:%d",
                c, r, v, i, p, cur);
    }

    private int seedCountries() {
        int created = 0;
        for (CountryEntity e : read("/catalog/countries.json", CountryEntity.class)) {
            if (!countries.codeExists(e.code)) {
                countries.persist(e);
                created++;
            }
        }
        return created;
    }

    private int seedRegions() {
        int created = 0;
        for (RegionEntity e : read("/catalog/regions-ci.json", RegionEntity.class)) {
            if (!regions.codeExists(e.code)) {
                regions.persist(e);
                created++;
            }
        }
        return created;
    }

    private int seedCities() {
        int created = 0;
        for (CityEntity e : read("/catalog/cities-ci.json", CityEntity.class)) {
            boolean exists = cities.find("regionCode = ?1 and name = ?2", e.regionCode, e.name)
                    .firstResultOptional()
                    .isPresent();
            if (!exists) {
                e.id = idGenerator.newId();
                cities.persist(e);
                created++;
            }
        }
        return created;
    }

    private int seedIndustries() {
        int created = 0;
        for (IndustryEntity e : read("/catalog/industries.json", IndustryEntity.class)) {
            if (!industries.codeExists(e.code)) {
                industries.persist(e);
                created++;
            }
        }
        return created;
    }

    private int seedPlans() {
        int created = 0;
        for (PlanEntity e : read("/catalog/plans.json", PlanEntity.class)) {
            if (plans.findByCode(e.code).isEmpty()) {
                plans.persist(e);
                created++;
            }
        }
        return created;
    }

    private int seedCurrencies() {
        int created = 0;
        for (CurrencyEntity e : read("/catalog/currencies.json", CurrencyEntity.class)) {
            if (!currencies.codeExists(e.code)) {
                currencies.persist(e);
                created++;
            }
        }
        return created;
    }

    private <T> List<T> read(String classpathPath, Class<T> type) {
        try (InputStream in = getClass().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Seed introuvable : " + classpathPath);
            }
            return objectMapper.readValue(
                    in,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type)
            );
        } catch (IOException e) {
            throw new RuntimeException("Lecture du seed " + classpathPath + " échouée : " + e.getMessage(), e);
        }
    }
}
