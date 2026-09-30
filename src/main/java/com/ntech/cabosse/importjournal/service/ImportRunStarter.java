package com.ntech.cabosse.importjournal.service;

import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * La trace d'un import qui démarre, avant qu'une seule ligne parte.
 *
 * <p>Elle naît avant le premier écrit : si le serveur tombe en cours de
 * route, elle reste en cours et dit qu'un import a commencé, ce qu'aucune
 * autre trace ne dirait.</p>
 */
@ApplicationScoped
public class ImportRunStarter {

    public ImportRunEntity starting(UUID runId, String domain, int rowsReceived,
                                    String actorEmail, String fileName) {
        ImportRunEntity e = new ImportRunEntity();
        e.id = runId;
        e.domain = domain;
        e.phase = "COMMIT";
        e.at = Instant.now();
        e.createdAt = e.at;
        e.actorEmail = actorEmail;
        e.fileName = fileName;
        e.rowsReceived = rowsReceived;
        e.status = "RUNNING";
        e.rejections = List.of();
        e.decisions = List.of();
        e.creations = List.of();
        return e;
    }
}
