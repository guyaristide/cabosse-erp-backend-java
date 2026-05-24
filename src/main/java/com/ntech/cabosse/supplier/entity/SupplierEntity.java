package com.ntech.cabosse.supplier.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/** Fournisseur (B2B). Tenant-scoped. */
public class SupplierEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    /** Raison sociale (si différente du nom commercial). */
    public String legalName;

    /** Identifiant fiscal (RCCM, NIF…). */
    public String taxNumber;

    public String email;
    public String phone;
    public String addressLine;
    public String cityName;
    public String countryCode;

    /** Contact principal. */
    public String contactName;

    /** Conditions de règlement libre ({@code 30j fin de mois}). */
    public String paymentTerms;

    public String notes;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
