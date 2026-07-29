package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 053 — remet les comptes d'achat, de vente et de stock sur la
 * numérotation SYSCOHADA.
 *
 * <p>Le plan semé utilisait {@code 601} pour les achats de matières
 * premières et {@code 701} pour les ventes de produits finis. Au SYSCOHADA
 * révisé, ces deux numéros désignent les <strong>marchandises</strong> :
 * les matières premières sont en {@code 602}, les produits finis vendus en
 * {@code 702}, leurs stocks en {@code 32} et non {@code 31}. La confusion
 * était sans conséquence tant que la marchandise n'existait pas comme
 * nature d'article ; elle en a dès que les deux coexistent, et un cabinet
 * la relève.</p>
 *
 * <p>Les écritures déjà passées sont déplacées avec les comptes. Leur
 * intention n'a jamais changé : ce sont des achats de matières premières et
 * des ventes de produits finis, seul le numéro était faux. Les laisser sur
 * l'ancien compte mélangerait durablement marchandises et matières dans la
 * balance, ce qui est précisément ce qu'on corrige.</p>
 *
 * <p><strong>À vérifier après passage</strong> : un exercice déjà clôturé
 * ou un fichier des écritures comptables déjà transmis porte les anciens
 * numéros. La reprise ne rejoue pas ces exports.</p>
 */
@ChangeUnit(id = "align_syscohada_merchandise_accounts", order = "053", author = "neiba")
public class M053_AlignSyscohadaMerchandiseAccounts {

    /** Ancien numéro vers nouveau, pour les comptes dont le sens se déplace. */
    private static final Map<String, String> MOVES = new LinkedHashMap<>(Map.of(
            "601000", "602000",   // achats de matières premières
            "701000", "702000",   // ventes de produits finis
            "310000", "320000",   // stocks de matières premières
            "603100", "603200"    // variation des stocks de matières premières
    ));

    /** Comptes à (re)créer avec leur libellé SYSCOHADA. */
    private static final String[][] CHART = {
            {"601000", "Achats de marchandises", "CHARGES"},
            {"602000", "Achats de matières premières et fournitures liées", "CHARGES"},
            {"603100", "Variation des stocks de marchandises", "CHARGES"},
            {"603200", "Variation des stocks de matières premières", "CHARGES"},
            {"310000", "Stocks de marchandises", "AUTRES"},
            {"320000", "Stocks de matières premières", "AUTRES"},
            {"701000", "Ventes de marchandises", "PRODUITS"},
            {"702000", "Ventes de produits finis", "PRODUITS"},
    };

    @Execution
    public void execute(MongoDatabase database) {
        moveEntries(database);
        realignChart(database);
        moveArticleOverrides(database);
    }

    /**
     * Déplace les lignes d'écriture. Le libellé de la ligne n'est pas
     * touché : il dit déjà ce qu'elle est.
     */
    private static void moveEntries(MongoDatabase database) {
        MongoCollection<Document> pieces = database.getCollection("journal_pieces");
        List<WriteModel<Document>> ops = new ArrayList<>();
        for (Document piece : pieces.find()) {
            Object rawEntries = piece.get("entries");
            if (!(rawEntries instanceof List<?> entries)) continue;
            boolean changed = false;
            for (Object raw : entries) {
                if (!(raw instanceof Document entry)) continue;
                String target = MOVES.get(entry.getString("account"));
                if (target == null) continue;
                entry.put("account", target);
                changed = true;
            }
            if (changed) {
                ops.add(new UpdateOneModel<>(
                        Filters.eq("_id", piece.get("_id")),
                        Updates.set("entries", entries)));
            }
        }
        if (!ops.isEmpty()) pieces.bulkWrite(ops);
    }

    /** Crée les comptes manquants et corrige les libellés déplacés. */
    private static void realignChart(MongoDatabase database) {
        MongoCollection<Document> accounts = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        for (String[] row : CHART) {
            Document existing = accounts.find(Filters.eq("number", row[0])).first();
            if (existing == null) {
                accounts.insertOne(new Document("_id", java.util.UUID.randomUUID())
                        .append("number", row[0])
                        .append("label", row[1])
                        .append("family", row[2])
                        .append("active", true)
                        .append("createdAt", now)
                        .append("updatedAt", now));
            } else {
                accounts.updateOne(Filters.eq("number", row[0]),
                        Updates.combine(
                                Updates.set("label", row[1]),
                                Updates.set("family", row[2]),
                                Updates.set("updatedAt", now)));
            }
        }
    }

    /**
     * Un article dont la fiche imposait explicitement l'ancien compte suit
     * le déplacement : le gérant avait choisi un sens, pas un numéro.
     */
    private static void moveArticleOverrides(MongoDatabase database) {
        MongoCollection<Document> articles = database.getCollection("articles");
        for (Map.Entry<String, String> move : MOVES.entrySet()) {
            articles.updateMany(
                    Filters.eq("purchaseChargeAccount", move.getKey()),
                    Updates.set("purchaseChargeAccount", move.getValue()));
            articles.updateMany(
                    Filters.eq("salesRevenueAccount", move.getKey()),
                    Updates.set("salesRevenueAccount", move.getValue()));
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Le déplacement inverse ramènerait les marchandises sur les comptes
        // des matières : on ne le rejoue pas à l'aveugle.
    }
}
