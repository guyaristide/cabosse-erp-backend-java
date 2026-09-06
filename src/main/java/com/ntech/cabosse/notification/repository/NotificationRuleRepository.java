package com.ntech.cabosse.notification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.ntech.cabosse.notification.entity.NotificationRuleEntity;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class NotificationRuleRepository {

    public static final String COLLECTION = "notification_rules";

    @Inject TenantMongoDatabaseProvider tenantDb;

    private MongoCollection<NotificationRuleEntity> coll() {
        return tenantDb.collection(COLLECTION, NotificationRuleEntity.class);
    }

    public Optional<NotificationRuleEntity> findByEvent(String eventCode) {
        return Optional.ofNullable(coll().find(Filters.eq("eventCode", eventCode)).first());
    }

    public List<NotificationRuleEntity> listAll() {
        return coll().find().into(new ArrayList<>());
    }

    /** Une règle par événement : l'écriture remplace ou crée. */
    public void upsert(NotificationRuleEntity e) {
        coll().replaceOne(Filters.eq("eventCode", e.eventCode), e,
                new ReplaceOptions().upsert(true));
    }
}
