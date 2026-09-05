package com.ntech.cabosse.treasury.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * Point de caisse : ce que la comptabilité attend en caisse à une date,
 * et le détail des mouvements qui y ont conduit.
 */
@Schema(description = "Point de caisse à une date")
public record CashPositionDto(
        UUID accountId, String accountLabel, String syscohadaAccount,
        LocalDate from, LocalDate at,
        BigDecimal opening,
        BigDecimal inflows,
        BigDecimal outflows,
        BigDecimal theoretical,
        /** Fonds partis mais non encore reçus à cette date. */
        BigDecimal inTransit,
        List<CashMovementDto> movements,
        CashCountResponseDto lastCount
) {}
