# Signer la convention de sous-traitance avec votre client

Avant la première mise en production, y compris pour un hébergement gratuit, vous devez avoir signé avec votre client un écrit à contenu imposé par l'article 28 du RGPD ; pour un intégrateur seul et une association, il tient en deux pages. Cette page donne les six points à y mettre, leur contenu pour cette application et ce qu'il ne faut pas signer. Ce n'est pas un avis juridique : la qualification exacte du responsable de traitement dans une association et la portée de l'engagement d'assistance méritent un regard de juriste.

L'échéance est la première mise en production, y compris pour un hébergement
gratuit. L'article 28 n'exige pas un contrat commercial : il exige un écrit
signé des deux parties, à contenu imposé.

Pour l'instant, un document autonome valant convention de sous-traitance
suffit. Le jour où des conditions générales de vente existent, il en devient
l'annexe sans être réécrit.

## Les six points imposés par l'art. 28.3

| Point | Ce qu'il contient ici |
| --- | --- |
| Objet, durée, nature et finalité | Hébergement et exploitation d'un outil de planification d'équipes, pour la durée de la mise à disposition |
| Catégories de données et de personnes | Voir le socle du [gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre), dont des **mineurs** : ce mot justifie les mesures renforcées |
| Obligations du sous-traitant | N'agir que sur instruction documentée, confidentialité des personnes autorisées, mesures de sécurité (voir [le gabarit d'entrée](rgpd.md#remplir-une-entrée-du-registre)), **pas de sous-traitance ultérieure sans autorisation** ; les briques de [la liste des sous-traitants ultérieurs](rgpd.md#qui-est-quoi) sont donc nommées dans l'écrit |
| Sort des données en fin de contrat | Restitution ou suppression, **au choix du responsable**, à trancher explicitement. La restitution s'appuie sur l'export SQL ([`import-export.md`](../comprendre/exports-et-archives.md)), la suppression sur la procédure de purge (voir la procédure de purge de [`exploitation.md`](exploitation.md)) |
| Assistance | Aide à répondre aux demandes d'exercice de droits, et **notification d'atteinte sans délai** ; la procédure et le délai engagé sont dans [la procédure d'atteinte](rgpd-atteinte-aux-donnees.md), d'où se recopie la clause |
| Audit | Mise à disposition des informations nécessaires pour démontrer la conformité : le registre de l'instance et la description de son durcissement ([Exposer l'instance sur Internet](securite.md)) |

## Ce qu'il ne faut pas signer

Ne recopiez pas un modèle de DPA générique. Ceux qui circulent sont écrits pour
des prestataires SaaS multi-clients et promettent ce qu'un intégrateur seul ne
peut pas tenir : astreinte, audit sur site, sous-traitants certifiés. Signer une
obligation qu'on ne tiendra pas est pire que ne rien signer, car c'est un
engagement opposable. Le modèle de la CNIL pour les petites structures est le
bon point de départ ; le tableau ci-dessus dit ce qu'il faut y mettre.
