package com.ntech.cabosse.logs.service;

import jakarta.ws.rs.core.StreamingOutput;

/**
 * Ce que le contrôleur sert : le nom du fichier et le flux qui l'écrit.
 *
 * <p>Le flux n'est consommé qu'au moment d'écrire la réponse : les
 * contrôles de période et de structure ont déjà eu lieu, une archive
 * refusée ne commence jamais à s'écrire.</p>
 */
public record LogsArchiveDownload(String filename, StreamingOutput body) {}
