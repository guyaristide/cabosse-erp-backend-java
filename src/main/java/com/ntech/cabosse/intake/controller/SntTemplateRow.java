package com.ntech.cabosse.intake.controller;

/** Une ligne d'exemple du modèle de détail de livraison par producteur. */
public record SntTemplateRow(
        String reference, String date, String weightKg, String amount,
        String amountCard, String amountCash, String producerName,
        String producerPhone, String delegateName, String delegatePhone) {}
