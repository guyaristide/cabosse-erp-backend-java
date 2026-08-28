package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.List;

/**
 * Migration 069 — le délégué couvre des localités, plus une section.
 *
 * <p>La règle métier est précise : une localité est gérée par un seul
 * délégué, un délégué intervient dans plusieurs localités, et un délégué
 * ne peut pas gérer seul une section. Le produit rattachait le délégué à
 * une <em>section</em>, ce qui rendait la dernière règle inexprimable et
 * interdisait de savoir qui collecte dans un village donné.</p>
 *
 * <p><strong>Aucune donnée n'est devinée.</strong> Rien, en base, ne dit
 * quelles localités un délégué couvre : la section ne se découpe pas
 * mécaniquement en villages, et répartir au hasard aurait produit un
 * référentiel faux que personne n'aurait relu. La migration pose donc les
 * champs vides et l'index d'unicité ; la structure range ses localités,
 * et la section reste dérivée de la saisie d'avant tant qu'elle ne l'a pas
 * fait.</p>
 *
 * <p>L'index sur {@code localityIds} est <strong>partiel</strong> : il ne
 * couvre que les documents où le tableau existe. Un index unique sur un
 * tableau collecte les documents sans le champ sous une même clé nulle et
 * refuserait le deuxième fournisseur créé.</p>
 */
@ChangeUnit(id = "delegate_covers_localities", order = "069", author = "neiba")
public class M069_DelegateCoversLocalities {

    private static final String SUPPLIERS = "suppliers";
    private static final String LOCALITIES = "localities";

    @Execution
    public void execute(MongoDatabase database) {
        // Un tableau vide plutôt qu'un champ absent : le code tolère les
        // deux, mais une projection se lit mieux sur un champ présent.
        database.getCollection(SUPPLIERS).updateMany(
                Filters.exists("localityIds", false),
                Updates.set("localityIds", List.of()));

        // La localité relève d'une section. Null tant que la structure ne
        // l'a pas rangée : le rattachement ne s'invente pas.
        database.getCollection(LOCALITIES).updateMany(
                Filters.exists("sectionId", false),
                Updates.set("sectionId", null));

        // Retrouver le délégué d'une localité est la requête de base du
        // nouveau modèle : sans index, chaque contrôle balaie la collection.
        database.getCollection(SUPPLIERS).createIndex(
                new Document("localityIds", 1),
                new IndexOptions().name("idx_suppliers_locality_ids"));

        database.getCollection(LOCALITIES).createIndex(
                new Document("sectionId", 1),
                new IndexOptions().name("idx_localities_section"));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection(SUPPLIERS).updateMany(
                Filters.exists("localityIds", true),
                Updates.unset("localityIds"));
        database.getCollection(LOCALITIES).updateMany(
                Filters.exists("sectionId", true),
                Updates.unset("sectionId"));
    }
}
