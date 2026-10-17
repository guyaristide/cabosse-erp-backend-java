package com.ntech.cabosse.shared.api;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les colonnes sur lesquelles une liste accepte d'être triée.
 *
 * <p>Une liste d'autorisation, et non une traduction libre. Le nom
 * qu'envoie la requête ne devient jamais une clé de tri Mongo sans
 * passer par ici : trier sur un champ sans index revient à parcourir
 * toute la collection, et accepter n'importe quel nom expose la forme
 * interne des documents à qui essaie.</p>
 *
 * <p>Un nom inconnu ne fait pas échouer la requête : il retombe sur le
 * tri par défaut. La réponse dit ensuite le tri réellement appliqué, de
 * sorte qu'un écran plus ancien que le serveur affiche la vérité plutôt
 * que sa demande. Refuser casserait la liste entière pour une colonne
 * disparue.</p>
 */
public final class SortFields {

    private final Map<String, String> allowed;
    private final String defaultField;
    private final boolean defaultAscending;

    private SortFields(Map<String, String> allowed, String defaultField, boolean defaultAscending) {
        this.allowed = allowed;
        this.defaultField = defaultField;
        this.defaultAscending = defaultAscending;
    }

    /** Démarre une déclaration, en nommant le tri par défaut de la liste. */
    public static Builder defaultSort(String name, boolean ascending) {
        return new Builder(name, ascending);
    }

    public static final class Builder {
        private final Map<String, String> allowed = new LinkedHashMap<>();
        private final String defaultName;
        private final boolean defaultAscending;

        private Builder(String defaultName, boolean defaultAscending) {
            this.defaultName = defaultName;
            this.defaultAscending = defaultAscending;
        }

        /** Une colonne triable : le nom public, et le champ du document. */
        public Builder on(String name, String field) {
            allowed.put(name, field);
            return this;
        }

        /** Colonne dont le nom public est déjà celui du document. */
        public Builder on(String name) {
            return on(name, name);
        }

        public SortFields build() {
            String field = allowed.get(defaultName);
            if (field == null) {
                throw new IllegalStateException(
                        "Le tri par défaut « " + defaultName + " » doit figurer parmi les colonnes triables.");
            }
            return new SortFields(Map.copyOf(allowed), defaultName, defaultAscending);
        }
    }

    /**
     * Le tri à appliquer pour cette requête : le demandé, ou celui par
     * défaut.
     *
     * <p>Une colonne refusée emporte son sens avec elle : le sens
     * appartient à la colonne demandée, le garder en retombant sur une
     * autre produirait un tri que personne n'a voulu.</p>
     */
    public Sort resolve(PageRequest pr) {
        boolean requested = pr != null && pr.sort() != null && allowed.containsKey(pr.sort());
        if (!requested) {
            return new Sort(defaultField, allowed.get(defaultField), defaultAscending);
        }
        boolean ascending = pr.direction() == null
                ? !pr.sort().equals(defaultField) || defaultAscending
                : !"desc".equalsIgnoreCase(pr.direction());
        return new Sort(pr.sort(), allowed.get(pr.sort()), ascending);
    }

    /**
     * Un tri résolu : le nom que l'écran affiche, le champ que Mongo
     * trie, et le sens.
     */
    public record Sort(String name, String field, boolean ascending) {

        public Document toDocument() {
            return new Document(field, ascending ? 1 : -1);
        }

        /** Le tri à renvoyer dans l'enveloppe paginée, pour que l'écran le dise. */
        public String[] names() {
            return new String[]{ name };
        }

        public String order() {
            return ascending ? "asc" : "desc";
        }
    }
}
