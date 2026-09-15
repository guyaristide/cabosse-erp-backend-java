package com.ntech.cabosse.importjournal.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Un motif de refus et les lignes qu'il a emportées.
 *
 * <p>C'est la lecture qui fait gagner du temps : un fichier refusé l'est
 * presque toujours pour une poignée de raisons répétées, jamais pour
 * trente-huit raisons différentes.</p>
 */
@Schema(description = "Un motif de refus et les lignes concernées")
public record ReasonGroupDto(String reason, int count, List<Integer> sampleRows) {}
