package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.jboss.logging.Logger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Le motif d'un refus, tel qu'on peut le lire.
 *
 * <p>Un traitement par lot (import, comptabilisation d'un bordereau)
 * attrape le refus de chaque ligne pour continuer les suivantes, et range
 * le motif dans le compte rendu. Le motif y arrivait brut : une écriture
 * Mongo refusée s'affichait « Write operation error on MongoDB server
 * mongo:27017. WriteError{code=11000, …} » en plein écran, devant
 * quelqu'un qui pèse des sacs de cacao (constaté le 06/10/2026).</p>
 *
 * <p>Un refus que nous avons écrit nous-mêmes s'adresse déjà à cette
 * personne : il passe tel quel. Tout le reste est un incident technique :
 * il part au journal avec sa pile d'appels, et l'écran ne reçoit qu'une
 * phrase et une référence, celle que le support cherchera dans le
 * journal.</p>
 */
public final class UserFacingReason {

    private static final Logger LOG = Logger.getLogger(UserFacingReason.class);

    private UserFacingReason() {}

    /** Le motif lisible, sans contexte de journal. */
    public static String of(Throwable error) {
        return of(error, null);
    }

    /**
     * Le motif lisible.
     *
     * @param context ce qu'on traitait au moment du refus (numéro de
     *                ligne, référence de pièce), repris dans le journal
     *                pour retrouver l'incident sans le montrer.
     */
    public static String of(Throwable error, String context) {
        if (error == null) return Messages.msg("m.internal-error");

        String written = writtenForHumans(error);
        if (written != null) return written;

        String reference = newReference();
        LOG.errorf(error, "Incident %s%s", reference,
                context == null || context.isBlank() ? "" : " sur " + context);
        return Messages.msg("m.technical-failure", reference);
    }

    /**
     * Le message est-il le nôtre ?
     *
     * <p>Les exceptions de ce paquet portent un texte du catalogue, écrit
     * pour la personne qui lit. Une contrainte de validation aussi. Rien
     * d'autre n'est montrable.</p>
     */
    private static String writtenForHumans(Throwable error) {
        if (error instanceof ConstraintViolationException violations) {
            String joined = violations.getConstraintViolations().stream()
                    .map(ConstraintViolation::getMessage)
                    .filter(m -> m != null && !m.isBlank())
                    .distinct()
                    .collect(Collectors.joining(" "));
            return joined.isBlank() ? null : joined;
        }
        boolean ours = error instanceof BusinessException
                || error instanceof NotFoundException
                || error instanceof ConflictException
                || error instanceof ForbiddenException
                || error instanceof UnauthorizedException
                || error instanceof TooManyRequestsException;
        if (!ours) return null;
        String message = error.getMessage();
        return message == null || message.isBlank() ? null : message;
    }

    /**
     * La référence que le support demandera.
     *
     * <p>Courte et sans ambiguïté à l'oral : on la dicte au téléphone.
     * Elle n'a pas à être unique au monde, seulement à retrouver une
     * ligne dans le journal du jour.</p>
     */
    public static String newReference() {
        return String.format("%08X", ThreadLocalRandom.current().nextInt());
    }
}
