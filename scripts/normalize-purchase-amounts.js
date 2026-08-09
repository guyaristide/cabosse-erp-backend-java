/**
 * Normalise en Decimal128 les montants d'achats producteurs stockés dans
 * un autre type numérique.
 *
 * Pourquoi : la migration M051 posait un zéro entier sur
 * `delegateMarginFcfa`. Le pilote Mongo refuse de décoder un montant qui
 * n'est pas un Decimal128, et l'échec ne porte pas sur la ligne fautive
 * mais sur la requête entière : un seul reçu mal typé fait tomber toute la
 * liste en erreur 500.
 *
 * La conversion est faite par le serveur, sur les seuls documents
 * concernés. Sans effet sur ce qui est déjà correct. Rejouable.
 *
 * Usage :
 *   mongosh "<uri>/<base_du_tenant>" scripts/normalize-purchase-amounts.js
 */

const FIELDS = [
  'weightKg',
  'guaranteedPricePerKgFcfa',
  'amountFcfa',
  'amountPaidFcfa',
  'creditImputedFcfa',
  'delegateMarginFcfa',
];

const NUMERIC = ['int', 'long', 'double'];

const selector = { $or: FIELDS.map((f) => ({ [f]: { $type: NUMERIC } })) };

const before = db.producer_purchases.countDocuments(selector);
print(`${before} reçu(s) portent un montant mal typé.`);

if (before > 0) {
  const conversions = {};
  for (const f of FIELDS) {
    conversions[f] = {
      $cond: [{ $in: [{ $type: `$${f}` }, NUMERIC] }, { $toDecimal: `$${f}` }, `$${f}`],
    };
  }
  const result = db.producer_purchases.updateMany(selector, [{ $set: conversions }]);
  print(`${result.modifiedCount} reçu(s) normalisés.`);
}

const after = db.producer_purchases.countDocuments(selector);
print(after === 0 ? 'Tous les montants sont en Decimal128.' : `Restent ${after} reçu(s) à corriger.`);
