package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.security.Roles;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migration 061 — profils du tenant et reprise des accès existants
 * (backlog ADM-01).
 *
 * <p>Jusqu'ici, le rôle {@code USER} ouvrait tout ce qui n'était pas
 * réservé à l'administrateur. En posant des droits sur les écritures
 * sensibles, ces comptes se retrouveraient sans accès du jour au
 * lendemain. La migration crée donc un profil <strong>Opérateur</strong>
 * qui reprend exactement ce qu'ils pouvaient faire, et le leur
 * attribue.</p>
 *
 * <p>C'est une reprise, pas une recommandation : ce profil accorde
 * large. L'administrateur du tenant est ensuite libre de le découper en
 * magasinier, comptable et directeur, ce que la plateforme ne pouvait pas
 * deviner à sa place.</p>
 */
@ChangeUnit(id = "tenant_roles_and_default_profile", order = "061", author = "neiba")
public class M061_TenantRolesAndDefaultProfile {

    private static final Logger LOG = Logger.getLogger(M061_TenantRolesAndDefaultProfile.class);

    private static final String OPERATOR_CODE = "OPERATEUR";

    /** Ce que le rôle USER ouvrait de fait avant les droits. */
    private static final List<Permission> OPERATOR = List.of(
            Permission.REFERENTIAL_READ, Permission.REFERENTIAL_WRITE,
            Permission.PURCHASE_READ, Permission.PURCHASE_WRITE, Permission.EXPENSE_WRITE,
            Permission.COLLECTION_READ, Permission.COLLECTION_RECEIPT_WRITE,
            // L'opérateur demande une avance ; il ne l'approuve pas et ne
            // la décaisse pas. C'est le sens même du découpage.
            Permission.COLLECTION_ADVANCE_REQUEST, Permission.COLLECTION_PAYMENT_WRITE,
            Permission.MEMBER_READ, Permission.MEMBER_WRITE, Permission.MEMBER_CREDIT_REQUEST,
            Permission.PARCEL_READ, Permission.PARCEL_WRITE, Permission.HARVEST_WRITE,
            Permission.PROCESSING_READ, Permission.PRODUCTION_WRITE,
            Permission.FERMENTATION_WRITE, Permission.DRYING_WRITE,
            Permission.STOCK_READ, Permission.STOCK_MOVE, Permission.STOCK_INVENTORY,
            Permission.SALE_READ, Permission.SALE_WRITE, Permission.SALE_PAYMENT,
            Permission.ACCOUNTING_READ, Permission.ACCOUNTING_WRITE, Permission.TREASURY_WRITE,
            Permission.EUDR_READ, Permission.EUDR_WRITE, Permission.TRACEABILITY_READ,
            Permission.REPORTING_READ,
            Permission.SETTINGS_READ);

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        var roles = database.getCollection("tenant_roles");
        roles.createIndex(Indexes.ascending("code"),
                new IndexOptions().name("uniq_tenant_roles_code").unique(true).background(true));

        if (roles.find(Filters.eq("code", OPERATOR_CODE)).first() != null) return;

        // Le profil ne propose que ce que les capacités du tenant rendent
        // applicable : une case sans objet n'est pas un droit.
        var caps = CapabilityMigrationGuard.capabilitiesFor(database.getName(), client);
        List<String> codes = OPERATOR.stream()
                .filter(p -> p.availableFor(caps))
                .map(Enum::name)
                .toList();

        UUID roleId = UUID.randomUUID();
        Instant now = Instant.now();
        roles.insertOne(new Document("_id", roleId)
                .append("code", OPERATOR_CODE)
                .append("name", "Opérateur")
                .append("description",
                        "Reprise des accès ouverts avant la mise en place des droits. "
                                + "À découper selon les métiers de la structure.")
                .append("permissions", new ArrayList<>(codes))
                .append("active", true)
                .append("createdAt", now)
                .append("updatedAt", now));

        // Rattachement des comptes existants, dans le plan de contrôle :
        // sans cela, ils perdraient l'accès au redémarrage.
        MongoDatabase control = client.getDatabase(ControlPlane.DATABASE);
        Document tenant = control.getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", database.getName())).first();
        if (tenant == null) return;

        var result = control.getCollection(ControlPlane.Collections.USERS).updateMany(
                Filters.and(
                        Filters.eq("tenantId", tenant.get("_id")),
                        Filters.eq("roles", Roles.USER),
                        Filters.or(Filters.exists("tenantRoleIds", false),
                                Filters.size("tenantRoleIds", 0))),
                Updates.set("tenantRoleIds", List.of(roleId)));
        LOG.infof("M061 : profil Opérateur créé (%d droits), attribué à %d compte(s)",
                codes.size(), result.getModifiedCount());
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("tenant_roles").drop();
    }
}
