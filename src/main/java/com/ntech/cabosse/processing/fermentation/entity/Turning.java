package com.ntech.cabosse.processing.fermentation.entity;

import java.time.Instant;

/** Opération de brassage / retournement du bac. */
public class Turning {
    public Instant at;
    public String operator;
    public String notes;

    public Turning() {}
}
