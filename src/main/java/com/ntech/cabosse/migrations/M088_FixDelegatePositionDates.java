package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * Répare les dates d'effet de position écrites en texte (27/09/2026).
 *
 * <p>{@code M086} posait {@code effectiveDate} avec
 * {@code LocalDate.now().toString()} : une chaîne, là où l'entité
 * déclare un {@code LocalDate}. À la relecture, le décodeur attendait
 * une date et trouvait du texte. Il ne rendait pas la position fautive
 * en moins : il faisait échouer <strong>tout l'appel</strong>, si bien
 * que l'état des avances aux délégués répondait 500 sans que rien
 * n'indique lequel des documents posait problème.</p>
 *
 * <p>Même forme que le défaut des montants en Decimal128 : un seul
 * document mal typé emporte la requête entière.</p>
 *
 * <p>{@code runAlways} parce que la conversion doit atteindre les
 * structures provisionnées entre deux livraisons, et parce que M086
 * reste rejouable : si elle reposait un jour du texte, celle-ci le
 * rattraperait au démarrage suivant. Elle ne touche que les documents
 * dont la valeur est une chaîne, donc la rejouer ne coûte rien.</p>
 */
@ChangeUnit(id = "fix_delegate_position_dates", order = "088", author = "neiba", runAlways = true)
public class M088_FixDelegatePositionDates {

    private static final String COLLECTION = "delegate_status_positions";

    @Execution
    public void execute(MongoDatabase database) {
        MongoCollection<Document> positions = database.getCollection(COLLECTION);

        // $type "string" : les documents déjà corrects sont ignorés, et
        // la migration se rejoue sans rien réécrire.
        for (Document position : positions.find(
                Filters.type("effectiveDate", "string"))) {
            Object raw = position.get("effectiveDate");
            Object id = position.get("_id");
            if (id == null || !(raw instanceof String text) || text.isBlank()) continue;

            Date converted;
            try {
                converted = Date.from(LocalDate.parse(text)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (Exception unparsable) {
                // Une valeur qu'on ne sait pas lire ne se devine pas : la
                // laisser en l'état vaut mieux que poser une date fausse
                // sur une décision de gouvernance.
                continue;
            }
            positions.updateOne(Filters.eq("_id", id),
                    Updates.set("effectiveDate", converted));
        }
    }

    @RollbackExecution
    public void rollback() {
        // Rien à défaire : remettre du texte recréerait la panne.
    }
}
