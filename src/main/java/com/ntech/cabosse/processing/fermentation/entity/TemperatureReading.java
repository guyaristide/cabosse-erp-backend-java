package com.ntech.cabosse.processing.fermentation.entity;

import java.math.BigDecimal;
import java.time.Instant;

/** Mesure de température d'un bac à un instant donné. */
public class TemperatureReading {
    public Instant at;
    public BigDecimal celsius;
    /** Observation libre ("sonde 1 centre", "matin", "après brassage"…). */
    public String observation;
    public String recordedByEmail;

    public TemperatureReading() {}
}
