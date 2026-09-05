package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/*
 * Extrait de son fichier-conteneur le 04/09/2026 : un fichier .java ne
 * porte qu'un seul type, règle de la maison rappelée par l'utilisateur.
 * Le propos d'ensemble du domaine vit dans le javadoc du service.
 */
/**
 * Le relevé complet.
 *
 * <p>Solde d'ouverture, totaux de la période et solde de clôture
 * portent sur <b>tout</b> ce qui est filtré, jamais sur la page
 * affichée. Un solde de clôture qui ne tiendrait compte que de vingt
 * lignes serait faux, et faux sans le dire.</p>
 *
 * <p>{@code sharedWith} nomme les autres comptes déclarés sur le même
 * compte du plan. Rien n'oblige aujourd'hui à leur en donner un
 * distinct : quand deux caisses partagent leur compte comptable, leurs
 * mouvements sont indiscernables, et le relevé le dit plutôt que de
 * laisser croire qu'il ne montre qu'un seul tiroir.</p>
 */
@Schema(description = "Relevé d'un compte de trésorerie sur une période")
public record AccountStatementDto(
        UUID accountId,
        String accountLabel,
        String syscohadaAccount,
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        BigDecimal totalIn,
        BigDecimal totalOut,
        BigDecimal closingBalance,
        java.util.List<String> sharedWith,
        Pagination<MovementDto> page
) {}
