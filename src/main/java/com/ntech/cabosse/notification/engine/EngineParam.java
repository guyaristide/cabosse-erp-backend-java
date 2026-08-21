package com.ntech.cabosse.notification.engine;

/**
 * Déclaration d'un paramètre attendu par un moteur. C'est cette
 * déclaration qui permet à l'écran d'administration de se dessiner tout
 * seul : ajouter un moteur au backend n'impose aucune modification du
 * back-office.
 *
 * @param code     nom du paramètre tel que stocké
 * @param label    libellé affiché à l'administrateur
 * @param secret   valeur chiffrée au repos et jamais relue par l'API
 * @param required sans elle, le moteur n'est pas utilisable
 * @param help     précision courte affichée sous le champ, ou null
 */
public record EngineParam(String code, String label, boolean secret,
                          boolean required, String help) {

    public static EngineParam required(String code, String label) {
        return new EngineParam(code, label, false, true, null);
    }

    public static EngineParam optional(String code, String label) {
        return new EngineParam(code, label, false, false, null);
    }

    public static EngineParam secret(String code, String label) {
        return new EngineParam(code, label, true, true, null);
    }

    public EngineParam withHelp(String helpText) {
        return new EngineParam(code, label, secret, required, helpText);
    }
}
