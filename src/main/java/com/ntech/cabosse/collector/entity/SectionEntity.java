package com.ntech.cabosse.collector.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Section de collecte de la coopérative (backlog ACH-02). Zone
 * géographique où la coopérative source sa matière première via un ou
 * plusieurs délégués collecteurs. Tenant-scoped et éditable.
 */
public class SectionEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;
    public String description;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
