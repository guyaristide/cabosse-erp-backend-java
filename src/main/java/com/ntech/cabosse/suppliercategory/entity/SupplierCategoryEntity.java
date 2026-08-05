package com.ntech.cabosse.suppliercategory.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Catégorie de fournisseur de matière première (backlog ACH-07).
 *
 * <p>Une coopérative ne reprend pas dans les mêmes conditions selon qui
 * apporte : un délégué collecteur qui rassemble la production de tout un
 * village n'est pas rémunéré comme un planteur qui vient déposer ses deux
 * sacs. La catégorie porte ces conditions et évite de les ressaisir
 * fournisseur par fournisseur.</p>
 *
 * <p>Référentiel tenant, éditable, sans catégorie semée : les catégories
 * d'une coopérative de cacao ne sont pas celles d'une unité de trituration
 * de palmier. Le mode et le taux laissés vides font hériter du réglage
 * tenant, ce qui permet de créer une catégorie pour son seul rôle de
 * classement.</p>
 */
public class SupplierCategoryEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;
    public String description;

    /**
     * Mode de rémunération propre à la catégorie : {@code NONE},
     * {@code PER_KG} ou {@code PERCENT}. {@code null} hérite du tenant.
     */
    public String marginMode;

    /** Taux appliqué dans ce mode. {@code null} hérite du tenant. */
    public BigDecimal marginRate;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
