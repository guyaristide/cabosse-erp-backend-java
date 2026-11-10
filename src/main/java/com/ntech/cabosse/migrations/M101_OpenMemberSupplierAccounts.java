package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.accounting.service.SubAccountNumbering;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Migration 101 — un compte auxiliaire à chaque sociétaire qui n'en a
 * pas, sous la racine que la structure a choisie (CPT-36, DEC-59 du
 * 10/11/2026).
 *
 * <p>Même geste que le bouton des Paramètres : « Producteur » suivi du
 * nom, numéros à la suite selon {@link SubAccountNumbering}, miroir
 * rattaché. Ici il se rejoue à chaque démarrage pour rattraper les fiches
 * créées entre deux, et il ne fait rien tant que la racine n'est pas
 * réglée : ouvrir des milliers de comptes sous une racine que personne
 * n'a choisie serait une décision prise à la place de la structure.</p>
 *
 * <p>{@code runAlways} : une fiche déjà pourvue est laissée telle quelle,
 * le rejeu ne duplique rien.</p>
 */
@ChangeUnit(id = "open_member_supplier_accounts", order = "101", author = "neiba", runAlways = true)
public class M101_OpenMemberSupplierAccounts {

    private static final Logger LOG = Logger.getLogger(M101_OpenMemberSupplierAccounts.class);

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        String root = configuredRoot(database.getName(), client);
        if (root == null) return;
        int opened = open(database, root);
        if (opened > 0) {
            LOG.infof("M101 : %d compte(s) fournisseur ouvert(s) aux sociétaires sous %s (%s)",
                    opened, root, database.getName());
        }
    }

    /** Ouvre les comptes manquants sous la racine ; renvoie combien l'ont été. */
    static int open(MongoDatabase database, String root) {
        MongoCollection<Document> chart = database.getCollection("chart_of_accounts");
        if (chart.find(Filters.eq("number", root)).first() == null) {
            LOG.warnf("M101 : racine %s absente du plan de %s, rien d'ouvert", root, database.getName());
            return 0;
        }
        String prefix = SubAccountNumbering.prefixOf(root);
        Set<String> taken = new HashSet<>();
        for (Document a : chart.find(Filters.regex("number", "^" + Pattern.quote(prefix)))
                .projection(Projections.include("number"))) {
            String n = a.getString("number");
            if (n != null) taken.add(n);
        }

        MongoCollection<Document> suppliers = database.getCollection("suppliers");
        int opened = 0;
        for (Document s : suppliers.find(Filters.and(
                        Filters.exists("memberId", true), Filters.ne("memberId", null),
                        Filters.or(Filters.exists("subsidiaryAccount", false),
                                Filters.eq("subsidiaryAccount", null),
                                Filters.eq("subsidiaryAccount", ""))))
                .projection(Projections.include("name", "collectiveAccount"))) {
            Optional<String> next = SubAccountNumbering.next(root, taken);
            if (next.isEmpty()) {
                LOG.warnf("M101 : plus aucun numéro libre sous %s (%s)", root, database.getName());
                break;
            }
            String number = next.get();
            Date now = new Date();
            chart.insertOne(new Document()
                    .append("_id", UuidCreator.getTimeOrderedEpoch())
                    .append("number", number)
                    .append("label", "Producteur " + (s.getString("name") == null ? "" : s.getString("name")))
                    .append("family", "TIERS")
                    .append("active", true)
                    .append("system", false)
                    .append("createdAt", now)
                    .append("updatedAt", now));
            taken.add(number);
            String collective = s.getString("collectiveAccount");
            suppliers.updateOne(Filters.eq("_id", s.get("_id")), Updates.combine(
                    Updates.set("subsidiaryAccount", number),
                    Updates.set("collectiveAccount", collective == null || collective.isBlank() ? root : collective),
                    Updates.set("updatedAt", now)));
            opened++;
        }
        return opened;
    }

    /** La racine réglée par la structure, ou {@code null} tant qu'elle ne l'a pas choisie. */
    private static String configuredRoot(String databaseName, MongoClient client) {
        Document tenant = client.getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", databaseName))
                .projection(Projections.include("preferences.memberSupplierAccountRoot"))
                .first();
        if (tenant == null) return null;
        Document prefs = tenant.get("preferences", Document.class);
        String root = prefs != null ? prefs.getString("memberSupplierAccountRoot") : null;
        return root == null || root.isBlank() ? null : root.trim();
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Les comptes ouverts restent : les retirer détacherait les fiches de ce qu'elles portent.
    }
}
