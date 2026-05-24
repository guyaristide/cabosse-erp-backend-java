package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.IndustryEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.List;

@ApplicationScoped
public class IndustryRepository implements PanacheMongoRepositoryBase<IndustryEntity, String> {

    public List<IndustryEntity> findAllActive() {
        return find("isActive", Sort.ascending("label"), true).list();
    }

    public boolean codeExists(String code) {
        return findByIdOptional(code).isPresent();
    }

    /** Renvoie la liste des codes inexistants parmi ceux fournis (pour la validation FK). */
    public List<String> findMissingCodes(Collection<String> codes) {
        return codes.stream()
                .filter(c -> !codeExists(c))
                .toList();
    }
}
