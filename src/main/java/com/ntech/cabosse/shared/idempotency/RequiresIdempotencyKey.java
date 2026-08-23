package com.ntech.cabosse.shared.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Impose la présence d'un en-tête {@code Idempotency-Key} sur l'endpoint.
 *
 * <p>À poser sur les écritures dont un rejeu accidentel coûte de l'argent
 * réel. L'exemple qui a motivé l'annotation : un règlement partiel
 * renvoyé après une coupure réseau repassait la garde « reste dû
 * suffisant » et payait le producteur une seconde fois. La protection
 * d'idempotence existe, mais optionnelle elle ne protège que les clients
 * disciplinés : sur ces flux, le serveur la rend obligatoire.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresIdempotencyKey {
}
