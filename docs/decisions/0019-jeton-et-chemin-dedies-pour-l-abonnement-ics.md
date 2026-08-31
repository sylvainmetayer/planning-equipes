# 0019 — Un jeton et un chemin dédiés pour l'abonnement au calendrier

- **Statut** : accepté
- **Date** : août 2026
- **Portée** : sécurité, API, modèle de données, déploiement

## Contexte

Le planning individuel s'exportait en `.ics` **téléchargé une fois**. Un
fichier téléchargé est une photo : après chaque republication, l'agenda de
l'animateur montre l'ancien planning, et personne ne pense à retélécharger.
Ce que les gens attendent d'un agenda, c'est une **adresse d'abonnement**
donnée une fois et rappelée toute seule.

Or un client d'agenda abonné est une machine anonyme : il ne porte aucun
cookie, ne sait pas répondre à un défi d'authentification et n'a personne au
clavier pour lire un code reçu par e-mail. Toutes les routes de l'espace
animateur exigent, **en plus** du jeton d'URL, une session ouverte par code
e-mail. Un abonnement sur ces routes recevrait donc un `401` dès la première
resynchronisation automatique.

Le point dur n'est pas technique, il est de périmètre. Le jeton d'espace ne
suffit aujourd'hui à **rien** tout seul : il permet de *demander* un code, et
c'est tout. Le lien qui le porte est imprimé sur un PDF individuel, donc il
circule — par mail, par capture d'écran, dans un dossier partagé. Ce
déséquilibre est voulu : le lien désigne quelqu'un, l'adresse e-mail prouve
que c'est bien lui.

## Options envisagées

| Option | Ce qu'elle coûte |
| --- | --- |
| **A.** Retirer `@EspaceSessionRequired` de la route `.ics` existante | Le jeton d'espace deviendrait, sur cette route, une preuve d'accès complète et durable au planning nominatif sans second facteur. Un PDF qui a fuité donnerait un flux permanent, et le seul moyen de le couper serait de faire tourner le jeton d'espace — donc de casser le lien imprimé sur tous les PDF déjà distribués. Une révocation qui a ce prix ne se fait pas |
| **B.** Un paramètre de requête « je suis un abonnement » sur la route existante | Le même élargissement, avec en plus une règle invisible : la sécurité d'une route dépendrait d'un paramètre, ce qu'aucun proxy et aucun lecteur ne voit |
| **C.** Un jeton dédié, sur un chemin dédié | Une colonne, une migration, un garde et un geste de révocation de plus |
| **D.** Une URL signée à durée limitée | Un abonnement dure une saison, pas une heure. Une signature qui expire redonne exactement le problème du fichier téléchargé, en plus opaque : l'agenda cesse de se mettre à jour sans rien dire |

## Décision

**Option C.** Un jeton d'abonnement distinct, sur un préfixe d'URL distinct.

- `animateur.abonnement_token`, à côté de `access_token` : même génération
  (`gen_random_uuid()`, 122 bits d'aléa), même unicité **globale** — le jeton
  arrive sur une URL publique sans en-tête d'édition à croire, il doit donc
  désigner son édition à lui seul.
- `GET /api/abonnements/{token}/planning.ics` — une seule route sous ce
  préfixe, exemptée de la politique d'authentification admin, comme l'espace
  l'est déjà.
- La révocation est une **rotation**, déclenchée **depuis l'espace animateur**
  lui-même, derrière le jeton d'espace *et* le code e-mail. Elle ne touche pas
  le jeton d'espace : le lien imprimé sur un PDF survit.
- Rien de publié répond un **calendrier vide en `200`**, jamais un `404`.

### Pourquoi le préfixe compte autant que le jeton

Un déploiement derrière un proxy d'accès qui authentifie ses visiteurs a besoin
d'un **motif d'URL** pour excepter ce flux, et ce motif doit ne désigner que
lui. Élargir l'exception à l'espace animateur ouvrirait du même geste toutes
ses routes, dont la seule écriture publique de l'application. Le préfixe est
donc une décision d'interface, pas un rangement : il fait partie de ce que
l'application promet à son exploitant, et il est stable à ce titre.

## Conséquences

- **Ce que le jeton d'abonnement permet est délibérément petit, et écrit
  quelque part** : un document, en lecture — le planning publié de son
  propriétaire. Pas l'espace, pas les échanges, pas les disponibilités, pas le
  PDF, aucune écriture. Les deux jetons ne se substituent pas l'un à l'autre,
  dans un sens comme dans l'autre, et un test le vérifie.
- **C'est malgré tout une donnée nominative exposée durablement sans second
  facteur**, et c'est le prix de la fonctionnalité. Il se consigne au registre
  des traitements plutôt que de se minimiser.
- Le jeton voyage **dans le chemin**, comme celui de l'espace — et il y revient
  bien plus souvent, un agenda se resynchronisant seul. Les journaux d'accès du
  reverse proxy doivent donc se purger ou s'écrire sans ces chemins.
- **Aucun limiteur de débit dédié.** La route ne fait ni authentification ni
  envoi de mail, et un plafond par personne ne distinguerait pas l'abus d'un
  usage normal (trois appareils abonnés au même flux). La limitation par IP du
  proxy reste la première ligne, comme pour les exports.
- **Pas de geste admin pour révoquer l'abonnement d'un tiers**, aujourd'hui.
  Supprimer la fiche coupe tout ; le bouton dédié est un ajout d'écran, pas de
  modèle, et se décidera quand quelqu'un le demandera.
- Une édition dupliquée repart avec des jetons neufs, comme pour l'espace : les
  colonnes ne sont pas copiées, le défaut de la base en frappe de nouveaux.
