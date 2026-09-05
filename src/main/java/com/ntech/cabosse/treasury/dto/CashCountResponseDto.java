package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.treasury.entity.CashCountEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
@Schema(description = "Comptage physique confronté au solde attendu")
public record CashCountResponseDto(
        UUID id, String ref, UUID accountId, String accountLabel,
        LocalDate countedAt,
        BigDecimal theoretical, BigDecimal counted, BigDecimal discrepancy,
        String pieceRef, String notes, String countedByEmail, Instant createdAt
) {
    public static CashCountResponseDto from(CashCountEntity e) {
        return new CashCountResponseDto(
                e.id, e.ref, e.accountId, e.accountLabel, e.countedAt,
                e.theoretical, e.counted, e.discrepancy,
                e.pieceRef, e.notes, e.countedByEmail, e.createdAt);
    }
}
