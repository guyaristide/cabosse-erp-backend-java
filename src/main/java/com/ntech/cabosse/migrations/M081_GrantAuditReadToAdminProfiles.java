package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.permission.entity.Permission;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 081 — le droit de consulter le journal d'audit rejoint les
 * profils qui portaient déjà l'administration.
 *
 * <p>Le journal n'était gardé que par le rôle d'administrateur de la
 * structure. En lui donnant son propre droit, on ouvre la possibilité de
 * le confier à un contrôleur ou à un expert-comptable sans lui donner
 * l'administration entière. Encore faut-il que les profils qui
 * l'administraient déjà ne le perdent pas au passage.</p>
 *
 * <p>Le droit est donc accordé aux profils qui portent la gestion des
 * utilisateurs : ce sont eux qui administrent la structure, et pour eux
 * l'accès au journal n'est pas une nouveauté mais un acquis.</p>
 */
/*
 * runAlways : le corps est conditionnel et idempotent. Le rejeu couvre le
 * profil créé entre deux démarrages, sans quoi un administrateur nommé
 * après la livraison perdrait un accès que ses pairs conservent.
 */
@ChangeUnit(id = "grant_audit_read_to_admin_profiles", order = "081", author = "neiba", runAlways = true)
public class M081_GrantAuditReadToAdminProfiles {

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("tenant_roles").updateMany(
                Filters.and(
                        Filters.eq("permissions", Permission.USER_MANAGE.name()),
                        Filters.ne("permissions", Permission.AUDIT_READ.name())),
                Updates.addToSet("permissions", Permission.AUDIT_READ.name()));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("tenant_roles").updateMany(
                Filters.eq("permissions", Permission.AUDIT_READ.name()),
                Updates.pull("permissions", Permission.AUDIT_READ.name()));
    }
}
