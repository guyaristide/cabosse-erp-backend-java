package com.ntech.cabosse.intake.controller;

/** Une ligne d'exemple du modèle d'import des bordereaux de réception. */
public record IntakeNoteTemplateRow(
        String campaignLabel, String productLabel, String date, String movement,
        String ref, String truckNumber, String supplierCode, String supplierName,
        String lineNumber, String grossWeightKg, String bagCount, String netWeightKg) {}
