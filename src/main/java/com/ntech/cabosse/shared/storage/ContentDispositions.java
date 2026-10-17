package com.ntech.cabosse.shared.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * L'en-tête de téléchargement, construit à partir d'un nom venu du client.
 *
 * <p>Le nom multipart était réinjecté tel quel, entre guillemets non
 * échappés : un nom contenant un guillemet ou un retour à la ligne
 * cassait l'en-tête, et avec lui le reste de la réponse (audit du
 * 17/10/2026). Deux formes sont écrites, comme le veut la RFC 6266 :
 * un nom ASCII sûr pour les clients anciens, et le nom réel encodé pour
 * les autres, qui le préfèrent.</p>
 */
public final class ContentDispositions {

    private ContentDispositions() {}

    /** Pour afficher dans le navigateur. */
    public static String inline(String fileName, String fallback) {
        return build("inline", fileName, fallback);
    }

    /** Pour enregistrer sur le poste. */
    public static String attachment(String fileName, String fallback) {
        return build("attachment", fileName, fallback);
    }

    static String build(String type, String fileName, String fallback) {
        String name = fileName == null || fileName.isBlank() ? fallback : fileName.trim();
        // Seuls les caractères imprimables ASCII restent dans la forme
        // quotée, et ni le guillemet ni la barre oblique inverse, qui
        // la termineraient ou l'échapperaient.
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replaceAll("[\"\\\\]", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
