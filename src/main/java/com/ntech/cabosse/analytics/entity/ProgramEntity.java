package com.ntech.cabosse.analytics.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Programme de la comptabilité budgétaire (backlog CPT-10). Tenant-scoped
 * et éditable. Un programme (ex. Durabilité) regroupe des projets financés
 * (ex. « Achat cacao certifié »). Les valeurs seedées (v11) sont
 * illustratives : la coopérative ajuste programmes et projets à ses
 * financements réels.
 *
 * <p>L'imputation budgétaire ne concerne que les charges (classe 6) et
 * les produits (classe 7) SYSCOHADA.</p>
 */
public class ProgramEntity {

    @BsonId
    public UUID id;

    /** Code court stable porté par les lignes de pièces (ex. {@code DURAB}). */
    public String code;

    public String name;
    public String description;

    public boolean active = true;

    /** Projets financés du programme (imbriqués — pas d'entité séparée). */
    public List<Project> projects = new ArrayList<>();

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public static class Project {
        /** Code court unique au sein du programme (ex. {@code CERT}). */
        public String code;
        public String name;
        public boolean active = true;
    }
}
