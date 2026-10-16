package com.ntech.cabosse.shared.imports;

import com.ntech.cabosse.supplier.entity.SupplierEntity;

import java.util.List;
import java.util.UUID;

/**
 * L'apporteur qu'une ligne de fichier importé nomme, retrouvé dans le
 * registre des fournisseurs.
 *
 * <p>La reconnaissance se faisait sur le nom exact, à la casse et aux
 * espaces près. Les extraits du terrain écrivent le délégué avec sa
 * section entre parenthèses, « TRAORE MOUSSA (DIOUROUZON) » quand le
 * registre tient « Traoré Moussa » : la ligne ressortait sans apporteur,
 * son bordereau restait vide, et rien ne disait pourquoi (signalé le
 * 16/10/2026 sur le bordereau 0896).</p>
 *
 * <p>Trois passes, de la plus sûre à la plus tolérante, chacune exigeant
 * un seul candidat. Le <strong>téléphone</strong> tranche seul. Le
 * <strong>nom</strong> se compare accents et ponctuation retirés, puis,
 * à défaut, sans la parenthèse de fin, qui porte une localité et non une
 * identité. Rien n'est deviné au-delà : deux candidats laissent la ligne
 * sans apporteur, avec son ambiguïté dite.</p>
 */
public record PartyMatch(UUID id, String named, boolean ambiguous) {

    /** Ce que donne une ligne qui ne nomme personne. */
    private static final PartyMatch NOBODY = new PartyMatch(null, null, false);

    public static PartyMatch of(String rawName, String rawPhone,
                                List<SupplierEntity> candidates) {
        String phone = ImportFields.phoneKey(rawPhone);
        String name = ImportFields.clean(rawName);
        if (phone == null && name == null) return NOBODY;

        if (phone != null) {
            List<SupplierEntity> hits = candidates.stream()
                    .filter(s -> phone.equals(ImportFields.phoneKey(s.phone))).toList();
            if (hits.size() == 1) return new PartyMatch(hits.get(0).id, name, false);
        }
        if (name == null) return NOBODY;

        // Les écritures du nom, de la plus fidèle à la plus tolérante :
        // la première qui désigne un seul fournisseur l'emporte.
        for (String key : NameAliases.of(name)) {
            List<SupplierEntity> hits = candidates.stream()
                    .filter(s -> s.name != null && NameAliases.of(s.name).contains(key))
                    .toList();
            if (hits.size() == 1) return new PartyMatch(hits.get(0).id, name, false);
            if (hits.size() > 1) return new PartyMatch(null, name, true);
        }
        return new PartyMatch(null, name, false);
    }
}
