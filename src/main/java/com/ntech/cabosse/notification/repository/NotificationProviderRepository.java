package com.ntech.cabosse.notification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fournisseurs configurés, dans le plan de contrôle : la configuration
 * des passerelles est une affaire de plateforme. Les surcharges de tenant
 * (expéditeur, langue) viendront dans les préférences du tenant, pas ici.
 */
@ApplicationScoped
public class NotificationProviderRepository {

    @Inject ControlPlaneProvider controlPlane;

    private MongoCollection<NotificationProviderEntity> coll() {
        return controlPlane.collection(
                ControlPlane.Collections.NOTIFICATION_PROVIDERS, NotificationProviderEntity.class);
    }

    public void insert(NotificationProviderEntity e) {
        coll().insertOne(e);
    }

    public void replace(NotificationProviderEntity e) {
        coll().replaceOne(Filters.eq("_id", e.id), e);
    }

    public void delete(UUID id) {
        coll().deleteOne(Filters.eq("_id", id));
    }

    public Optional<NotificationProviderEntity> findById(UUID id) {
        return Optional.ofNullable(coll().find(Filters.eq("_id", id)).first());
    }

    public List<NotificationProviderEntity> listAll() {
        return coll().find().sort(Sorts.ascending("channel", "label")).into(new ArrayList<>());
    }

    /** Fournisseurs actifs d'un canal. L'ordre utile est calculé par usage. */
    public List<NotificationProviderEntity> listActive(NotificationChannel channel) {
        return coll().find(Filters.and(
                        Filters.eq("channel", channel.name()),
                        Filters.eq("active", true)))
                .into(new ArrayList<>());
    }

    /** Y a-t-il au moins un fournisseur actif sur ce canal ? */
    public boolean hasActive(NotificationChannel channel) {
        return coll().find(Filters.and(
                        Filters.eq("channel", channel.name()),
                        Filters.eq("active", true)))
                .limit(1)
                .first() != null;
    }
}
