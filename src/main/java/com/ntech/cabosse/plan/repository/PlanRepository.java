package com.ntech.cabosse.plan.repository;

import com.ntech.cabosse.plan.entity.PlanEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository {@link PlanEntity}. La clé primaire est le {@code code}
 * (string), pas un UUID.
 */
@ApplicationScoped
public class PlanRepository implements PanacheMongoRepositoryBase<PlanEntity, String> {

    public Optional<PlanEntity> findByCode(String code) {
        return findByIdOptional(code);
    }

    public List<PlanEntity> findActivePlans() {
        return list("active", true);
    }
}
