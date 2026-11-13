package com.ntech.cabosse.crm.repository;

import com.ntech.cabosse.crm.entity.TenantFollowUpEntity;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

/** La prochaine action par structure, dans le plan de contrôle. */
@ApplicationScoped
public class TenantFollowUpRepository implements PanacheMongoRepositoryBase<TenantFollowUpEntity, UUID> {

    public Optional<TenantFollowUpEntity> findByTenant(UUID tenantId) {
        return find("tenantId", tenantId).firstResultOptional();
    }
}
