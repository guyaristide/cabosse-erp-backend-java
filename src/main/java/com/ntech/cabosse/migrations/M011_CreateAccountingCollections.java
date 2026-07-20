package com.ntech.cabosse.migrations;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Migration 011 — collections du module Comptabilité (M8) + seed initial
 * du plan comptable SYSCOHADA minimal utilisé par le moteur de
 * comptabilisation automatique.
 *
 * <p>Tournée sur chaque base tenant via {@code TenantMigrationRunner}. La
 * collection {@code chart_of_accounts} est seedée avec les ~13 comptes
 * indispensables : tiers (401, 411), achats (601, 604, 6081, 624), ventes
 * (701), TVA (4456, 4457), trésorerie (521, 530). Le tenant peut ajouter
 * des sous-comptes ensuite ; le moteur résout par numéro, donc tant que
 * ces comptes existent toute écriture auto fonctionne.</p>
 *
 * <p>Les comptes seedés portent {@code system=true} — la suppression sera
 * refusée côté service.</p>
 */
@ChangeUnit(id = "create_accounting_collections", order = "011", author = "neiba")
public class M011_CreateAccountingCollections {

    @Execution
    public void execute(MongoDatabase database) {
        // ─── chart_of_accounts ───
        database.getCollection("chart_of_accounts").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("number"),
                        new IndexOptions().unique(true).name("uniq_chart_number")
                ),
                new IndexModel(
                        Indexes.ascending("family"),
                        new IndexOptions().name("idx_chart_family")
                )
        ));
        seedChartOfAccounts(database);

        // ─── bank_accounts ───
        database.getCollection("bank_accounts").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("syscohadaAccount"),
                        new IndexOptions().name("idx_bank_syscohada")
                ),
                new IndexModel(
                        Indexes.ascending("active"),
                        new IndexOptions().name("idx_bank_active")
                )
        ));

        // ─── journal_pieces ───
        database.getCollection("journal_pieces").createIndexes(List.of(
                new IndexModel(
                        Indexes.ascending("ref"),
                        new IndexOptions().unique(true).name("uniq_journal_ref")
                ),
                new IndexModel(
                        // Idempotence : un seul (sourceType, sourceId) par pièce.
                        Indexes.compoundIndex(
                                Indexes.ascending("sourceType"),
                                Indexes.ascending("sourceId")
                        ),
                        new IndexOptions().unique(true).name("uniq_journal_source")
                ),
                new IndexModel(
                        Indexes.descending("date"),
                        new IndexOptions().name("idx_journal_date_desc")
                ),
                new IndexModel(
                        Indexes.ascending("entries.syscohadaAccount"),
                        new IndexOptions().name("idx_journal_account")
                ),
                new IndexModel(
                        Indexes.ascending("sourceId"),
                        new IndexOptions().name("idx_journal_sourceId")
                )
        ));
    }

    /** Seed idempotent : chaque insert est protégé par check d'existence sur {@code number}. */
    private void seedChartOfAccounts(MongoDatabase database) {
        var coll = database.getCollection("chart_of_accounts");
        Instant now = Instant.now();
        List<Document> seeds = List.of(
                account("401",  "Fournisseurs",                            "FOURNISSEURS"),
                account("411",  "Clients",                                 "CLIENTS"),
                account("601",  "Achats de matières premières",            "CHARGES"),
                account("604",  "Achats stockés : autres approvisionnements","CHARGES"),
                account("6081", "Achats d'emballages",                     "CHARGES"),
                account("624",  "Transports sur achats",                   "CHARGES"),
                account("701",  "Ventes de produits finis",                "PRODUITS"),
                account("4456", "TVA déductible sur achats",               "TVA"),
                account("4457", "TVA collectée sur ventes",                "TVA"),
                account("521",  "Banque : compte courant",                 "TRESORERIE"),
                account("571",  "Caisse",                                  "TRESORERIE"),
                account("31",   "Stocks de marchandises",                  "AUTRES"),
                account("6031", "Variation des stocks de marchandises",    "CHARGES"),
                account("44566","État, TVA déductible sur achats",         "TVA"),
                account("461",  "Associés, opérations sur le capital",     "AUTRES"),
                account("1018", "Capital souscrit : parts sociales",       "AUTRES")
        );
        for (Document seed : seeds) {
            boolean exists = coll.countDocuments(Filters.eq("number", seed.getString("number"))) > 0;
            if (!exists) {
                seed.put("_id", UuidCreator.getTimeOrderedEpoch());
                seed.put("active", true);
                seed.put("system", true);
                seed.put("createdAt", now);
                seed.put("updatedAt", now);
                coll.insertOne(seed);
            }
        }
    }

    private Document account(String number, String label, String family) {
        return new Document("number", number)
                .append("label", label)
                .append("family", family);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("chart_of_accounts").drop();
        database.getCollection("bank_accounts").drop();
        database.getCollection("journal_pieces").drop();
    }
}
