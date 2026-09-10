package com.ntech.cabosse.outflow.controller;

/** Une ligne d'exemple du modèle d'import des bordereaux de sortie. */
public record OutflowNoteTemplateRow(
        String campaignLabel, String productLabel, String date, String movement,
        String ref, String dispatchNoteNumber, String loadingNumber,
        String truckNumber, String destination, String customerCode,
        String customerName, String lineNumber, String grossWeightKg,
        String bagCount, String netWeightKg) {}
