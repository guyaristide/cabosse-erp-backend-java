package com.ntech.cabosse.members.dto;

import com.ntech.cabosse.members.service.MemberFileField;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * État du dossier producteur (backlog MEM-09) : complétude et fraîcheur.
 * Dérivé à la lecture, jamais stocké.
 *
 * @param completenessPct part des informations attendues effectivement
 *                        renseignées, de 0 à 100
 * @param missingFieldCodes codes des informations manquantes. C'est ce sur
 *        quoi un client doit s'appuyer : stable, traduisible de son côté,
 *        insensible à une reformulation.
 * @param missingFields   <b>déprécié</b> : les mêmes informations sous forme
 *        d'intitulés français. Conservé le temps que les clients basculent
 *        sur les codes, et volontairement <b>laissé en français</b> plutôt
 *        que traduit : un client qui compare encore ces chaînes verrait sa
 *        comparaison échouer sans erreur le jour où un utilisateur passerait
 *        en anglais. Les deux champs partent ensemble, l'ordre de
 *        déploiement des services n'a donc pas d'importance.
 * @param expiresAt       date au-delà de laquelle l'enquête est périmée,
 *                        null si aucune enquête n'a été datée
 * @param expired         vrai si {@code expiresAt} est dépassée
 */
@Schema(description = "Complétude et fraîcheur du dossier producteur")
public record MemberFileStatusDto(
        int completenessPct,
        List<String> missingFieldCodes,
        List<String> missingFields,
        LocalDate expiresAt,
        boolean expired
) {

    /**
     * Construit l'état à partir des champs manquants.
     *
     * <p>Les intitulés français sont dérivés ici, à un seul endroit, pour
     * que leur retrait futur se fasse sans chercher qui les fabriquait.</p>
     */
    public static MemberFileStatusDto of(int completenessPct, List<MemberFileField> missing,
                                         LocalDate expiresAt, boolean expired) {
        return new MemberFileStatusDto(
                completenessPct,
                missing.stream().map(Enum::name).toList(),
                missing.stream().map(MemberFileStatusDto::legacyLabel).toList(),
                expiresAt,
                expired);
    }

    /** Intitulés historiques, figés : ils ne servent qu'à la compatibilité. */
    private static String legacyLabel(MemberFileField field) {
        return switch (field) {
            case LAST_NAME -> "Nom";
            case FIRST_NAMES -> "Prénoms";
            case GENDER -> "Genre";
            case BIRTH_DATE -> "Date de naissance";
            case BIRTH_PLACE -> "Lieu de naissance";
            case IDENTITY_DOCUMENT -> "Pièce d'identité";
            case PHONE -> "Téléphone";
            case VILLAGE -> "Village";
            case SECTION -> "Section";
            case PARCEL -> "Parcelle";
            case HOUSEHOLD -> "Composition du ménage";
            case CENSUS -> "Recensement";
            case COLLECTION_DATE -> "Date de collecte";
            case TRADE_REGISTER -> "Registre du commerce";
            case LEGAL_REPRESENTATIVE -> "Représentant légal";
        };
    }
}
