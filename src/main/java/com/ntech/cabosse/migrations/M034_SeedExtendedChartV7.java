package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Migration 034 — plan comptable étendu conforme aux jeux d'écritures v7
 * (backlog CPT-11). Idempotente : chaque compte n'est inséré que s'il
 * n'existe pas déjà (les tenants qui ont personnalisé leur plan ne sont
 * pas écrasés). Sert aussi de valeurs candidates au compte d'achat par
 * article ({@code ArticleEntity.purchaseChargeAccount}).
 */
@ChangeUnit(id = "seed_extended_chart_v7", order = "034", author = "neiba")
public class M034_SeedExtendedChartV7 {

    /** Comptes v7 : {numéro, libellé, famille}. */
    static final String[][] ACCOUNTS = {
            // Achats de produits agricoles (négoce)
            {"6011", "Achats de cacao marchand", "CHARGES"},
            {"6012", "Achats de cacao certifié", "CHARGES"},
            {"6013", "Achats de café", "CHARGES"},
            {"6014", "Achats d'anacarde", "CHARGES"},
            {"6015", "Achats d'hévéa", "CHARGES"},
            {"6016", "Achats de palmier à huile", "CHARGES"},
            {"6017", "Achats d'autres produits vivriers", "CHARGES"},
            // Intrants agricoles
            {"6021", "Achats d'engrais", "CHARGES"},
            {"6022", "Achats de produits phytosanitaires", "CHARGES"},
            {"6023", "Achats de semences et plants", "CHARGES"},
            {"6024", "Achats de matériel de pépinière", "CHARGES"},
            {"6025", "Achats de carburants et lubrifiants", "CHARGES"},
            {"6026", "Achats d'équipements de protection (EPI)", "CHARGES"},
            // Emballages et stockage
            {"6041", "Achats de sacs et emballages", "CHARGES"},
            {"6042", "Achats de matériel de stockage", "CHARGES"},
            {"6043", "Achats d'étiquettes et supports de traçabilité", "CHARGES"},
            // Entretien du matériel
            {"6051", "Achats de pièces détachées", "CHARGES"},
            {"6052", "Achats de consommables informatiques", "CHARGES"},
            // Services
            {"612",  "Transports de produits", "CHARGES"},
            {"616",  "Assurances", "CHARGES"},
            {"618",  "Formations", "CHARGES"},
            {"622",  "Audits et certifications", "CHARGES"},
            {"627",  "Télécommunications et internet", "CHARGES"},
            {"628",  "Autres services extérieurs", "CHARGES"},
            {"632",  "Maintenance et prestations techniques", "CHARGES"},
            {"658",  "Charges diverses : actions sociales", "CHARGES"},
            // Immobilisations
            {"244",  "Matériel et outillage", "IMMOBILISATIONS"},
            {"245",  "Matériel de transport", "IMMOBILISATIONS"},
            // Stocks et variations non encore seedés
            {"33",   "Stocks d'autres approvisionnements", "AUTRES"},
            {"34",   "Produits en cours", "AUTRES"},
            {"36",   "Stocks de produits finis", "AUTRES"},
            {"6033", "Variation des stocks d'autres approvisionnements", "CHARGES"},
            {"734",  "Variation des en-cours", "PRODUITS"},
            {"736",  "Variation des stocks de produits fabriqués", "PRODUITS"},
            // Capitaux, résultat et impôt (fin d'exercice, CPT-12)
            {"101",  "Capital social", "AUTRES"},
            {"121",  "Report à nouveau", "AUTRES"},
            {"13",   "Résultat net de l'exercice", "AUTRES"},
            {"441",  "État, impôt sur les bénéfices", "AUTRES"},
            {"891",  "Impôt sur le résultat", "AUTRES"},
    };

    @Execution
    public void execute(MongoDatabase database) {
        var coll = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        for (String[] a : ACCOUNTS) {
            boolean exists = coll.countDocuments(Filters.eq("number", a[0])) > 0;
            if (!exists) {
                coll.insertOne(new Document("number", a[0])
                        .append("label", a[1])
                        .append("family", a[2])
                        .append("_id", UuidCreator.getTimeOrderedEpoch())
                        .append("active", true)
                        .append("system", true)
                        .append("createdAt", now)
                        .append("updatedAt", now));
            }
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // No-op assumé : les comptes ajoutés peuvent déjà porter des écritures.
    }

    /** Réutilisé par M011 pour seeder les nouveaux tenants d'un bloc. */
    public static List<String[]> accounts() {
        return List.of(ACCOUNTS);
    }
}
