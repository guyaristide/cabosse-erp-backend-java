package com.ntech.cabosse.treasury.dto;

import com.ntech.cabosse.shared.api.Pagination;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Le relevé interne d'un compte de trésorerie.
 *
 * <p>Énoncé par l'utilisateur comme grille de lecture : « la section banque
 * constate toute opération qui s'est conclue par un encaissement ou un
 * décaissement par chèque, la section caisse toute opération qui s'est
 * conclue par la caisse ». Cette lecture n'existait nulle part. Le
 * rapprochement confronte à un document externe, l'état des flux agrège par
 * période ; aucun écran ne répondait à « qu'est-il sorti de ce compte, et
 * pourquoi ».</p>
 *
 * <p>Ce n'est donc ni l'un ni l'autre : c'est le relevé que la structure
 * tient elle-même, où chaque ligne porte l'opération qui l'a produite.</p>
 */
public final class AccountStatementDtos {

    private AccountStatementDtos() {}

    /** Sens d'un mouvement, du point de vue du compte. */
    public enum Direction { IN, OUT }

    @Schema(description = "Un mouvement du compte, et l'opération qui l'a produit")
    public record MovementDto(
            LocalDate date,
            @Schema(description = "Référence de la pièce comptable") String pieceRef,
            String libelle,
            @Schema(description = "IN pour une entrée, OUT pour une sortie") String direction,
            BigDecimal amountFcfa,
            @Schema(description = "Solde du compte après ce mouvement") BigDecimal balanceFcfa,
            @Schema(description = "Nature de l'opération d'origine, en code") String sourceType,
            @Schema(description = "Identifiant de l'opération, pour y renvoyer") UUID sourceId,
            String sourceRef,
            UUID campaignId
    ) {}

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
            BigDecimal openingBalanceFcfa,
            BigDecimal totalInFcfa,
            BigDecimal totalOutFcfa,
            BigDecimal closingBalanceFcfa,
            java.util.List<String> sharedWith,
            Pagination<MovementDto> page
    ) {}
}
