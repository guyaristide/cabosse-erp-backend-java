package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.RegionEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class RegionRepository implements PanacheMongoRepositoryBase<RegionEntity, String> {

    public List<RegionEntity> findByCountry(String countryCode) {
        return find("countryCode = ?1 and isActive = ?2",
                Sort.ascending("name"), countryCode, true).list();
    }

    public boolean codeExists(String code) {
        return findByIdOptional(code).isPresent();
    }
}
