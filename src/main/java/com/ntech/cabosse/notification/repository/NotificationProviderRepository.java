package com.ntech.cabosse.notification.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.conversions.Bson;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.ProviderScope;
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

    /**
     * Fournisseurs actifs d'un canal à un niveau donné. L'ordre utile est
     * calculé par usage.
     *
     * @param tenantId structure propriétaire, ou {@code null} pour le
     *                 niveau plateforme
     */
    public List<NotificationProviderEntity> listActive(NotificationChannel channel, UUID tenantId) {
        return coll().find(scoped(channel, tenantId)).into(new ArrayList<>());
    }

    /**
     * Un fournisseur actif existe-t-il sur ce canal, à n'importe quel
     * niveau ?
     *
     * <p>Sert la sortie anticipée du relais, qui s'exécute avant d'ouvrir
     * le contexte d'une structure et ne peut donc pas raisonner par
     * tenant. Interroger le seul niveau plateforme ferait sauter le tour
     * de toutes les coopératives dès que l'éditeur n'a rien déclaré.</p>
     */
    public boolean hasAnyActive(NotificationChannel channel) {
        return coll().find(Filters.and(
                        Filters.eq("channel", channel.name()),
                        Filters.eq("active", true)))
                .limit(1).first() != null;
    }

    /** Y a-t-il au moins un fournisseur actif à ce niveau, sur ce canal ? */
    public boolean hasActive(NotificationChannel channel, UUID tenantId) {
        return coll().find(scoped(channel, tenantId)).limit(1).first() != null;
    }

    /** Les fournisseurs déclarés par une structure, actifs ou non. */
    public List<NotificationProviderEntity> listOfTenant(UUID tenantId) {
        return coll().find(Filters.eq("tenantId", tenantId))
                .sort(Sorts.ascending("channel", "label")).into(new ArrayList<>());
    }

    /** Les fournisseurs de la plateforme, actifs ou non. */
    public List<NotificationProviderEntity> listOfPlatform() {
        return coll().find(Filters.eq("scope", ProviderScope.PLATFORM.name()))
                .sort(Sorts.ascending("channel", "label")).into(new ArrayList<>());
    }

    /**
     * Un fournisseur de plateforme ne porte pas de structure ; celui d'une
     * coopérative se reconnaît à la sienne. Filtrer sur la portée seule
     * mêlerait les coopératives entre elles.
     */
    private Bson scoped(NotificationChannel channel, UUID tenantId) {
        return Filters.and(
                Filters.eq("channel", channel.name()),
                Filters.eq("active", true),
                tenantId == null
                        ? Filters.eq("scope", ProviderScope.PLATFORM.name())
                        : Filters.eq("tenantId", tenantId));
    }
}
