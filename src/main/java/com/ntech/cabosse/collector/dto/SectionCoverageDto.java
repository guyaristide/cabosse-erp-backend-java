package com.ntech.cabosse.collector.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Couverture d'une section par ses délégués.
 *
 * <p>La règle de l'expert tient en trois phrases : une localité est gérée
 * par un seul délégué, un délégué intervient dans plusieurs localités, et
 * <strong>un délégué ne peut pas gérer seul une section</strong>. Les deux
 * premières sont des contraintes d'écriture, refusées à la saisie. La
 * troisième ne peut pas l'être : en remplissant le référentiel on passe
 * forcément par un état où le premier délégué saisi détient tout, et
 * bloquer l'enregistrement rendrait la mise en place impossible.</p>
 *
 * <p>Elle devient donc un <strong>contrôle</strong>, lisible à tout moment :
 * l'état dit quelles localités n'ont pas de délégué et quelles sections
 * reposent sur un seul homme. Ce que le rattachement à la section
 * n'exprimait pas du tout.</p>
 */
@Schema(description = "Couverture des sections par les délégués")
public record SectionCoverageDto(
        List<Section> sections,
        /** Localités sans section : elles échappent à tout contrôle. */
        List<Locality> unassignedLocalities
) {
    public record Section(
            UUID sectionId,
            String sectionCode,
            String sectionName,
            int localityCount,
            /** Localités de la section qu'aucun délégué ne couvre. */
            List<Locality> uncoveredLocalities,
            List<Delegate> delegates,
            /**
             * Vrai quand un seul délégué couvre toutes les localités de la
             * section. Contraire à la règle, sans être bloquant.
             */
            boolean heldByASingleDelegate) {}

    public record Locality(UUID id, String code, String name) {}

    public record Delegate(UUID supplierId, String code, String name, int localityCount) {}
}
