# Renseigner les mentions légales

Avant d'ouvrir l'instance à ses animateurs, vous renseignez dans l'environnement les informations légales de l'organisateur : sans cinq d'entre elles (les cinq clés exigées de `LEGAL_MENTIONS`), l'application refuse de démarrer sur une instance **publique** (`PUBLIC_INSTANCE=true`) et le dit par un avertissement partout ailleurs. Voici lesquelles, pourquoi, et comment tenir une instance de démonstration qui n'en a pas.

## Pourquoi elles ne restent pas vides

Les mentions valent `""` par défaut, et la page publique `/mentions-legales` le dit d'elle-même : « Aucune information légale n'a été renseignée sur ce déploiement ». C'est acceptable sur une instance de démonstration, pas sur une instance en service, où la LCEN veut un éditeur et un hébergeur identifiés, et le RGPD un responsable de traitement, une base légale et une durée de conservation.

Ces informations sont **celles de l'organisateur** : c'est lui l'employeur des personnes planifiées et le responsable de traitement. Vous, qui hébergez, êtes **sous-traitant** au sens de l'article 28 du RGPD, ce qui suppose un écrit entre les deux parties dès le premier hébergement, y compris gratuit. Son contenu imposé et le registre qui l'accompagne sont dans [ce que l'hébergeur doit écrire et tenir](rgpd.md).

## Les variables à remplir

Les mentions se posent par **une variable**, `LEGAL_MENTIONS`, de la forme `clé=valeur;clé=valeur` : un point-virgule littéral dans une valeur s'écrit `\;`, et une clé inconnue est signalée au démarrage puis ignorée.

| Clé de `LEGAL_MENTIONS` | Statut | Ce qu'elle porte |
| --- | --- | --- |
| `editeur` | **exigée** | Éditeur : nom, forme juridique, adresse, immatriculation |
| `hebergeur` | **exigée** | Hébergeur : nom et adresse |
| `contact` | **exigée** | Adresse de contact, y compris pour exercer ses droits |
| `base-legale` | **exigée** | Base légale du traitement des données personnelles |
| `conservation` | **exigée** | Durée de conservation des données personnelles |
| `responsable-traitement` | facultative | Responsable de traitement, s'il diffère de l'éditeur ; vide, c'est l'éditeur |
| `directeur-publication` | facultative | Directeur de la publication |

Exemple, dans le `.env` du compose :

```
LEGAL_MENTIONS="editeur=Association Exemple, 1 rue des Pions, 79000 Niort;hebergeur=Hébergeur Fictif SAS, Paris;contact=planning@exemple.invalid;base-legale=Intérêt légitime de l'organisateur;conservation=Jusqu'au 31 décembre de l'année suivant l'édition"
```

Les cinq exigées font échouer le démarrage en production **sur une instance publique** quand elles sont vides, comme `ADMIN_PASSWORD` resté sur sa valeur d'exemple. Sur une instance qui n'est pas déclarée publique (`PUBLIC_INSTANCE` absente ou `false`), la même phrase est écrite en **avertissement** dans le journal de démarrage et l'application démarre : une instance que seule votre équipe atteint n'a personne à qui publier une page. Dans les deux cas, le message les nomme **toutes en une fois** : les corriger une par une coûterait cinq redémarrages.

Les deux autres restent facultatives volontairement. `responsable-traitement` vide retombe sur l'éditeur, et l'exiger contredirait ce repli. Un directeur de publication n'est pas exigé de tout éditeur.

Soyez attentif à `conservation` : cette durée s'affiche dans la politique de confidentialité, et **rien dans le code ne l'applique**. N'annoncez que ce que vous tiendrez réellement, par la [purge annuelle](conservation-des-donnees.md#la-purge-annuelle).

Les sept anciennes variables (`LEGAL_EDITEUR`, `LEGAL_HEBERGEUR`, `LEGAL_CONTACT`, `LEGAL_BASE_LEGALE`, `LEGAL_CONSERVATION`, `LEGAL_RESPONSABLE_TRAITEMENT`, `LEGAL_DIRECTEUR_PUBLICATION`) sont encore lues pendant toute la 4.x, avec un avertissement au démarrage, et retirées en 5.0 : voir [Passer de la 3.x à la 4.0](passer-a-la-4-0.md).

## La déclaration d'accessibilité

Une seconde variable, `LEGAL_ACCESSIBILITE`, remplit la page `/declaration-accessibilite` par quatre clés, `etat`, `date-audit`, `contenus` et `signalement` : l'état de conformité au RGAA, la date de l'audit, les contenus non accessibles, l'adresse de signalement. Elles ne bloquent pas le démarrage : l'obligation de l'article 47 de la loi n° 2005-102 ne pèse que sur certaines organisations, et la page vide dit elle-même qu'aucun audit n'est déclaré plutôt que d'annoncer une conformité. Le détail est dans [l'accessibilité](accessibilite.md).

## Une instance qui n'est pas en service

Une démonstration ou une recette pose `LEGAL_DEMO_INSTANCE=true` et démarre avec une page vide, sans refus ni avertissement. Le drapeau est explicite plutôt que déduit de `PUBLIC_URL` ou du mode de lancement : ce qu'une instance est *faite pour* ne se lit nulle part dans sa configuration ([décision 0040](repo:docs/decisions/0040-mentions-legales-exigees-au-demarrage.md)). Ne le posez pas sur une instance ouverte à ses animateurs.

Un serveur de démonstration ou de recette peut aussi montrer le mode jour J et l'espace animateur hors saison. Lancé avec `HORLOGE_SIMULEE_AUTORISEE=true`, il affiche dans *Paramètres*, onglet *Instance* (derrière le bouton « Réglages avancés »), la carte « Date et heure simulées » : une date, une heure facultative, et « Revenir à l'horloge de la machine ». Tant qu'une date est posée, un sablier rouge dans la barre du haut le rappelle et ramène à cette carte. Sans la variable, la carte n'apparaît pas et l'API refuse l'écriture (`400`). Ne l'activez jamais en production : elle ferait mentir l'écran jour J sur un vrai événement. Le détail est dans [la note de l'API](../comprendre/api-jour-j-et-animateurs.md#figer-la-date-du-jour).

## Monter de version avec une instance déjà en service

Si votre instance en service est publique (`PUBLIC_INSTANCE=true`) et n'a jamais renseigné ces variables, elle **ne redémarrera pas** tant qu'elles ne le sont pas. C'est voulu : l'obligation existait déjà, rien ne la tenait. Préparez-les avant la fenêtre de mise à jour, pas pendant. Une instance non déclarée publique démarre et écrit un avertissement : en 3.x, le refus valait pour toute instance, sans cette distinction ; **déclarez `PUBLIC_INSTANCE=true` si l'instance est ouverte à des inconnus**, sans quoi plus rien ne vous empêche de la publier sans mentions.

## Vérifier

Après redémarrage, `scripts/verifier-deploiement.sh` contrôle que les cinq mentions exigées ne sont pas vides ([le détail du script](installation.md#mettre-la-pile-en-route)), et la page `/mentions-legales` de l'instance affiche ce que vous avez saisi.
