package com.ntech.cabosse.user.entity;

/**
 * Le sens d'une exception de droit posée sur une personne.
 *
 * <p>Les deux existent parce que le besoin est symétrique. Un profil ne
 * donne pas assez à quelqu'un, et le compléter le donnerait à tous ceux
 * qui le portent ; ou un profil lui donne trop, et l'amputer priverait
 * les autres. Sans les deux sens, il faut fabriquer un profil sur mesure
 * pour une seule personne, ce que les exceptions existent justement pour
 * éviter (backlog ADM-03).</p>
 */
public enum PermissionExceptionMode {

    /** Ajoute le droit par-dessus les profils. */
    GRANT,

    /** Retire le droit que les profils accordent. */
    REVOKE
}
