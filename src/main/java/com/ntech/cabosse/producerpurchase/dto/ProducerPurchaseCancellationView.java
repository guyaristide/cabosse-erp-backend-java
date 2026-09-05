package com.ntech.cabosse.producerpurchase.dto;

import java.math.BigDecimal;
import java.time.Instant;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 */
/** Ce que la contre-passation a défait, pour un contrôle sans requête. */
public record ProducerPurchaseCancellationView(
        String reason,
        String cancelledByEmail,
        Instant cancelledAt,
        String reversalPieceRef,
        BigDecimal advanceCreditedBack,
        BigDecimal creditRestored
) {}
