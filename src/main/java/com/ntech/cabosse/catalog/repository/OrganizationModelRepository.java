package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.OrganizationModelEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class OrganizationModelRepository
        implements PanacheMongoRepositoryBase<OrganizationModelEntity, String> {

    public List<OrganizationModelEntity> findAllSorted() {
        return findAll(Sort.ascending("label")).list();
    }

    public boolean codeExists(String code) {
        return findByIdOptional(code).isPresent();
    }
}
