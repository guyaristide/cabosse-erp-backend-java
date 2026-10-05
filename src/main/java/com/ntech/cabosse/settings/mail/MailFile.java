package com.ntech.cabosse.settings.mail;

/**
 * Un fichier joint à un courrier.
 *
 * <p>Le contenu voyage en mémoire : une facture tient en quelques
 * centaines de kilo-octets, et la stocker pour la relire à l'envoi
 * ajouterait un fichier à garder alors que personne n'y reviendra.</p>
 *
 * @param filename    le nom que le destinataire verra
 * @param contentType le type déclaré par le formulaire
 * @param content     le binaire
 */
public record MailFile(String filename, String contentType, byte[] content) {

    public boolean isEmpty() {
        return content == null || content.length == 0;
    }
}
