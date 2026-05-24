package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.CountryEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Repository {@link CountryEntity}. Clé = code ISO 3166-1 alpha-2.
 */
@ApplicationScoped
public class CountryRepository implements PanacheMongoRepositoryBase<CountryEntity, String> {

    /** Pays actifs triés par nom français. */
    public List<CountryEntity> findAllActive() {
        return find("isActive", Sort.ascending("nameFr"), true).list();
    }

    public boolean codeExists(String code) {
        return findByIdOptional(code).isPresent();
    }
}
