package com.ntech.cabosse.catalog.repository;

import com.ntech.cabosse.catalog.entity.CurrencyEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class CurrencyRepository implements PanacheMongoRepository<CurrencyEntity> {

    public Optional<CurrencyEntity> findByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return find("_id", code.toUpperCase()).firstResultOptional();
    }

    public boolean codeExists(String code) {
        if (code == null || code.isBlank()) return false;
        return count("_id", code.toUpperCase()) > 0;
    }

    public java.util.List<CurrencyEntity> listActiveOrdered() {
        return list("isActive = true order by _id asc");
    }
}
