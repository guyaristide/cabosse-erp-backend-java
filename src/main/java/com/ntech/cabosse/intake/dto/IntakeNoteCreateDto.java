package com.ntech.cabosse.intake.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Saisie d'un bordereau de réception au magasin.
 *
 * <p>Ouverte le 13/09/2026 : le bordereau ne pouvait naître que d'un
 * import de carnet. Un magasinier qui voit arriver un camion n'a pas de
 * fichier à importer, et le parcours s'arrêtait là, sans qu'aucun écran
 * ne dise pourquoi.</p>
 *
 * <p>Mêmes champs que la correction, à quoi s'ajoutent la référence et le
 * délégué qui livre. Le bordereau constate une arrivée : il n'écrit ni
 * stock ni dette, c'est la comptabilisation qui crée les reçus d'achat,
 * seule voie d'entrée de la matière.</p>
 */
public record IntakeNoteCreateDto(
        /** Le numéro porté par le bordereau papier, unique dans la structure. */
        @NotBlank @Size(max = 60) String ref,
        @NotNull LocalDate date,
        /** Magasin d'entrée : le magasinier le sait, le comptable non. */
        java.util.UUID siteId,
        /** Le délégué qui descend la matière, quand il est au référentiel. */
        java.util.UUID delegateSupplierId,
        @Size(max = 160) String supplierName,
        @Size(max = 120) String productLabel,
        @Size(max = 60) String truckNumber,
        @DecimalMin(value = "0", message = "{v.valeur-negative-interdite}") BigDecimal grossWeightKg,
        Integer bagCount,
        @NotNull @DecimalMin(value = "0.001", message = "{v.valeur-negative-interdite}")
        BigDecimal netWeightKg
) {}
