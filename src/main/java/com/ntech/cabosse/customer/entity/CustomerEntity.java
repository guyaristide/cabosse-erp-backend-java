package com.ntech.cabosse.customer.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Client (B2B ou B2C). Tenant-scoped. */
public class CustomerEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    /** {@link CustomerType} sérialisé en String. */
    public String type;

    public String legalName;
    public String taxNumber;

    public String email;
    public String phone;
    public String addressLine;
    public String cityName;
    public String countryCode;

    public String contactName;

    /** Plafond de crédit (FCFA). {@code null} = pas de limite. */
    public BigDecimal creditLimit;

    public String notes;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
