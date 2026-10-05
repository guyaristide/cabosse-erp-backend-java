package com.ntech.cabosse.cashsupply.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Ce que la caisse enregistre en allant chercher l'argent.
 *
 * <p>La banque se choisit ici et non à la demande : la caissière sait le
 * jour du retrait ce qui est disponible, quand la direction ne le savait
 * pas la semaine d'avant.</p>
 *
 * <p>Le montant n'y figure pas : c'est celui qui a été accordé. Le
 * laisser saisir ici rendrait l'accord décoratif.</p>
 */
@Schema(description = "Exécution d'un approvisionnement accordé")
public record FulfillCashSupplyDto(

        @NotNull(message = "{v.compte-d-origine-requis}")
        @Schema(description = "Compte en banque sur lequel le chèque est tiré")
        UUID bankAccountId,

        @Size(max = 40, message = "{v.numero-de-cheque-trop-long}")
        @Schema(description = "Numéro du chèque préparé")
        String chequeNumber,

        @Schema(description = "Date du retrait. Aujourd'hui si absente.")
        LocalDate sentAt,

        @Size(max = 120, message = "{v.nom-du-porteur-trop-long}")
        @Schema(description = "Personne qui transporte les fonds")
        String carrierName

) {}
