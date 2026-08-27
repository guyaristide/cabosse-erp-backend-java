package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.shared.i18n.Messages;

/**
 * Déclaration d'un paramètre attendu par un moteur. C'est cette
 * déclaration qui permet à l'écran d'administration de se dessiner tout
 * seul : ajouter un moteur au backend n'impose aucune modification du
 * back-office.
 *
 * <p>Le libellé et l'aide sont désignés par une <b>clé de catalogue</b>,
 * pas par leur texte : l'écran de configuration des fournisseurs est
 * servi à l'administrateur d'un tenant, qui n'est pas forcément
 * francophone. Ils se résolvent à la lecture, dans la langue de la
 * requête.</p>
 *
 * @param code     nom du paramètre tel que stocké
 * @param labelKey clé du libellé affiché à l'administrateur
 * @param secret   valeur chiffrée au repos et jamais relue par l'API
 * @param required sans elle, le moteur n'est pas utilisable
 * @param helpKey  clé d'une précision courte affichée sous le champ, ou null
 */
public record EngineParam(String code, String labelKey, boolean secret,
                          boolean required, String helpKey) {

    /** Libellé dans la langue de la requête. */
    public String label() {
        return Messages.msg(labelKey);
    }

    /** Aide dans la langue de la requête, ou null s'il n'y en a pas. */
    public String help() {
        return helpKey == null ? null : Messages.msg(helpKey);
    }

    /**
     * Aide qui cite une valeur par défaut. La phrase vient du catalogue,
     * la valeur non : une URL ne se traduit pas.
     */
    public EngineParam withDefault(String value) {
        return new EngineParam(code, labelKey, secret, required,
                Messages.msg("m.ntf-p-default-value", value));
    }

    public static EngineParam required(String code, String labelKey) {
        return new EngineParam(code, labelKey, false, true, null);
    }

    public static EngineParam optional(String code, String labelKey) {
        return new EngineParam(code, labelKey, false, false, null);
    }

    public static EngineParam secret(String code, String labelKey) {
        return new EngineParam(code, labelKey, true, true, null);
    }

    public EngineParam withHelp(String helpKey) {
        return new EngineParam(code, labelKey, secret, required, helpKey);
    }
}
