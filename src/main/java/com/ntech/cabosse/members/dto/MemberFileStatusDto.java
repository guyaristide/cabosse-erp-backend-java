package com.ntech.cabosse.members.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * État du dossier producteur (backlog MEM-09) : complétude et fraîcheur.
 * Dérivé à la lecture, jamais stocké.
 *
 * @param completenessPct part des informations attendues effectivement
 *                        renseignées, de 0 à 100
 * @param missingFields   libellés des informations manquantes, en français,
 *                        prêts à afficher
 * @param expiresAt       date au-delà de laquelle l'enquête est périmée,
 *                        null si aucune enquête n'a été datée
 * @param expired         vrai si {@code expiresAt} est dépassée
 */
@Schema(description = "Complétude et fraîcheur du dossier producteur")
public record MemberFileStatusDto(
        int completenessPct,
        List<String> missingFields,
        LocalDate expiresAt,
        boolean expired
) {}
