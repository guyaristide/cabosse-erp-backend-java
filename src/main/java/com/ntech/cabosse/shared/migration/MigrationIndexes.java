package com.ntech.cabosse.shared.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexModel;
import org.bson.Document;

import java.util.List;

/**
 * Pose des index sans se heurter à leur forme d'hier.
 *
 * <p>MongoDB refuse de créer un index dont le nom existe déjà sous une
 * autre forme (erreur 86, {@code IndexKeySpecsConflict}). Ce refus est sain
 * en temps normal ; il devient un piège avec les migrations en rejeu.</p>
 *
 * <p>Le scénario s'est produit en production. Une migration de création
 * pose un index unique ; une migration ultérieure le refait en index
 * partiel, parce que la règle métier a changé. La première étant en rejeu,
 * elle repasse à chaque démarrage — et <em>avant</em> la seconde, puisque
 * son ordre est plus petit. Sur une base provisionnée avant le changement,
 * elle réclame la forme nouvelle et trouve l'ancienne : elle échoue, la
 * chaîne s'arrête, et la migration qui aurait réparé l'index n'est jamais
 * atteinte. Le verrou est circulaire, et il bloque du même coup toutes les
 * migrations suivantes, y compris celles qui n'ont rien à voir.</p>
 *
 * <p>La réparation est de traiter le nom de l'index comme son identité :
 * si la forme en place diffère de celle demandée, on retire l'ancienne
 * avant de poser la nouvelle. On ne retire jamais par précaution, seulement
 * après un conflit avéré, et le tenant converge vers la forme du code
 * déployé quel que soit son point de départ.</p>
 *
 * <p>Un index sans nom explicite reste à la charge de l'appelant : Mongo le
 * nomme d'après ses clés, et rien ne permet alors de désigner celui à
 * retirer. Toutes nos migrations nomment leurs index.</p>
 */
public final class MigrationIndexes {

    /** {@code IndexKeySpecsConflict} : même nom, forme différente. */
    private static final int INDEX_KEY_SPECS_CONFLICT = 86;

    private MigrationIndexes() {
    }

    /**
     * Pose chaque index, en remplaçant celui qui porterait le même nom sous
     * une autre forme.
     *
     * <p>Un par un, et non en lot : un seul conflit ferait échouer la
     * création groupée, y compris pour les index qui n'ont aucun problème.</p>
     */
    public static void ensure(MongoCollection<Document> collection, List<IndexModel> models) {
        for (IndexModel model : models) {
            ensureOne(collection, model);
        }
    }

    /** Variante à un seul index, pour les migrations qui n'en posent qu'un. */
    public static void ensure(MongoCollection<Document> collection, IndexModel model) {
        ensureOne(collection, model);
    }

    private static void ensureOne(MongoCollection<Document> collection, IndexModel model) {
        try {
            collection.createIndexes(List.of(model));
        } catch (MongoCommandException e) {
            String name = model.getOptions().getName();
            if (e.getErrorCode() != INDEX_KEY_SPECS_CONFLICT || name == null) {
                throw e;
            }
            collection.dropIndex(name);
            collection.createIndexes(List.of(model));
        }
    }
}
