package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.CityEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CityRepository implements PanacheMongoRepositoryBase<CityEntity, UUID> {

    public List<CityEntity> findByRegion(String regionCode) {
        return find("regionCode = ?1 and isActive = ?2",
                Sort.ascending("name"), regionCode, true).list();
    }

    public List<CityEntity> findByCountry(String countryCode) {
        return find("countryCode = ?1 and isActive = ?2",
                Sort.ascending("name"), countryCode, true).list();
    }

    public boolean idExists(UUID id) {
        return findByIdOptional(id).isPresent();
    }
}
