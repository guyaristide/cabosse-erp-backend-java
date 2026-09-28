package com.ntech.cabosse.supplier.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un délégué touche, campagne par campagne.
 *
 * <p>La liste remplace celle en place : retirer une campagne revient à
 * dire qu'aucun taux particulier n'a été convenu pour elle, et le taux
 * commun du délégué reprend la main. Un envoi partiel laisserait des
 * campagnes anciennes actives sans que personne ne les voie.</p>
 *
 * <p>Rangé du côté du fournisseur, où la qualité de délégué se porte
 * réellement : un délégué peut n'être pas sociétaire, et la fiche du
 * producteur n'est qu'une des deux portes vers ces taux.</p>
 */
@Schema(description = "Rémunération d'un délégué, campagne par campagne")
public record CollectorMarginsDto(
        @Valid List<Entry> margins
) {

    @Schema(description = "Taux convenu pour une campagne")
    public record Entry(
            @NotNull(message = "{v.campagne-requise}") UUID campaignId,

            /**
             * Dans l'unité du mode retenu par la structure : FCFA par kilo,
             * ou pourcentage. Le mode ne se choisit pas ici : changer
             * d'unité en cours d'exercice rendrait deux campagnes
             * incomparables.
             */
            @NotNull(message = "{v.taux-requis}")
            @DecimalMin(value = "0", message = "{v.montant-positif-requis}")
            BigDecimal rate
    ) {}
}
