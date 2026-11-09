package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

import java.time.Instant;

/**
 * Migration 100 — « Délégué douteux » devient « Autre délégué » (DEL-12,
 * demandé le 09/11/2026 par la coopérative).
 *
 * <p>Le libellé est une donnée du référentiel tenant, pas une constante :
 * M086 sème désormais le nouveau nom pour les tenants à venir, et cette
 * migration reprend ceux qui portent encore l'ancien. Le code
 * {@code DOUBTFUL}, la valeur d'alerte et l'ordre ne bougent pas, les
 * filtres et les liens partagés restent donc valables.</p>
 *
 * <p>Chaque position recopie le libellé du statut au moment où elle est
 * prise ; l'état des avances et la fiche du délégué liraient encore
 * l'ancien nom sans la seconde passe.</p>
 *
 * <p>{@code runAlways} : ne touche que le libellé exact d'origine, donc un
 * tenant qui l'aurait déjà renommé à sa façon garde son choix, et le
 * rejeu ne change rien à un tenant déjà repris.</p>
 */
@ChangeUnit(id = "rename_doubtful_delegate_status", order = "100", author = "neiba", runAlways = true)
public class M100_RenameDoubtfulDelegateStatus {

    static final String OLD_LABEL = "Délégué douteux";
    static final String NEW_LABEL = "Autre délégué";

    @Execution
    public void execute(MongoDatabase database) {
        database.getCollection("delegate_statuses").updateMany(
                Filters.and(Filters.eq("code", "DOUBTFUL"), Filters.eq("label", OLD_LABEL)),
                Updates.combine(Updates.set("label", NEW_LABEL), Updates.set("updatedAt", Instant.now())));
        database.getCollection("delegate_status_positions").updateMany(
                Filters.eq("statusLabel", OLD_LABEL),
                Updates.set("statusLabel", NEW_LABEL));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        database.getCollection("delegate_statuses").updateMany(
                Filters.and(Filters.eq("code", "DOUBTFUL"), Filters.eq("label", NEW_LABEL)),
                Updates.set("label", OLD_LABEL));
        database.getCollection("delegate_status_positions").updateMany(
                Filters.eq("statusLabel", NEW_LABEL),
                Updates.set("statusLabel", OLD_LABEL));
    }
}
