package com.ntech.cabosse.tenant.service;

/**
 * Ce qu'un passage sur les licences a changé.
 *
 * @param noticed   structures nouvellement averties de leur échéance
 * @param suspended structures suspendues, délai de grâce écoulé
 */
public record ExpirySweep(int noticed, int suspended) {}
