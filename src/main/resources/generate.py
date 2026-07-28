#!/usr/bin/env python3

import random
from datetime import datetime, timedelta

import yaml

# Configuration initiale
date_debut_str = "2026-07-08"
date_debut = datetime.strptime(date_debut_str, "%Y-%m-%d")
nb_jours = 15
nb_stands = 120
nb_animateurs = 250

data = {
    "festival": {"dateDebut": date_debut_str},
    "creneaux": [],
    "stands": [],
    "animateurs": []
}

# --- 1. GÉNÉRATION DES CRÉNEAUX ---
jours_nocturnes = [3, 4, 10, 11, 14]

for jour in range(1, nb_jours + 1):
    current_date = (date_debut + timedelta(days=jour-1)).strftime("%Y-%m-%d")

    heures = [("10:00", "12:00"), ("12:00", "14:00"), ("14:00", "16:00"),
              ("16:00", "18:00"), ("18:00", "20:00")]

    for i, (h_debut, h_fin) in enumerate(heures, start=1):
        data["creneaux"].append({
            "id": f"J{jour}-S{i}",
            "jour": jour,
            "date": current_date,
            "heureDebut": h_debut,
            "heureFin": h_fin
        })

    if jour in jours_nocturnes:
        data["creneaux"].append({
            "id": f"J{jour}-NOCTURNE",
            "jour": jour,
            "date": current_date,
            "heureDebut": "20:00",
            "heureFin": "02:00"
        })

# --- 2. GÉNÉRATION DES 120 STANDS ---
typologies = ["ROLE", "STRATEGIE", "COOPERATIF", "ENFANT", "ENIGME", "ADRESSE", "AMBIANCE"]

for i in range(1, nb_stands + 1):
    data["stands"].append({
        "id": f"STAND-{i:03d}",
        "nom": f"Stand {random.choice(typologies).capitalize()} {i:03d}",
        "typologiesProposees": random.sample(typologies, k=random.randint(1, 2)),
        "effectifMin": random.randint(2, 3),
        "effectifMax": random.randint(4, 5),
        "reserveMajeurs": random.choice([True, False, False])
    })

# --- 3. GÉNÉRATION DES 250 ANIMATEURS ---
prenoms = ["Nina", "Jules", "Berenice", "Iris", "Adrien", "Karim", "Tania", "Thibault", "Noemie", "Yasmine"]
noms = ["Blanc", "Roux", "Richard", "Henry", "Petit", "Michel", "Garcia", "Simon", "Muller", "Morin"]

# Plage de dates pour avoir des animateurs entre 18 et 65 ans en 2026
dob_start = datetime.strptime("1961-01-01", "%Y-%m-%d")
dob_end = datetime.strptime("2012-12-31", "%Y-%m-%d")
dob_delta_days = (dob_end - dob_start).days

for i in range(1, nb_animateurs + 1):
    if i <= 25:
        statut = "MANAGER"
    else:
        statut = random.choice(["BENEVOLE", "BENEVOLE", "SALARIE"])

    if i <= 125:
        niveau = random.choice(["AUTONOME", "REFERENT"])
    else:
        niveau = "DEBUTANT"

    jours_indispos = []
    if i > 125:
        nb_jours_indispo = random.randint(1, 2)
        jours_indispos = [
            (date_debut + timedelta(days=random.randint(0, nb_jours-1))).strftime("%Y-%m-%d")
            for _ in range(nb_jours_indispo)
        ]
        jours_indispos = list(set(jours_indispos))

    # Génération de la date de naissance aléatoire
    random_dob = dob_start + timedelta(days=random.randint(0, dob_delta_days))

    data["animateurs"].append({
        "id": f"A{i}",
        "prenom": random.choice(prenoms),
        "nom": random.choice(noms),
        "dateNaissance": random_dob.strftime("%Y-%m-%d"),
        "statut": statut,
        "competences": {
            random.choice(typologies): niveau
        },
        "joursIndisponibles": jours_indispos
    })

# --- SAUVEGARDE AU FORMAT YAML ---
class InlineList(list): pass
class InlineDict(dict): pass

def inline_list_rep(dumper, data):
    return dumper.represent_sequence(u'tag:yaml.org,2002:seq', data, flow_style=True)
def inline_dict_rep(dumper, data):
    return dumper.represent_mapping(u'tag:yaml.org,2002:map', data, flow_style=True)

yaml.add_representer(InlineList, inline_list_rep)
yaml.add_representer(InlineDict, inline_dict_rep)

for k in ["creneaux", "stands", "animateurs"]:
    data[k] = [InlineDict(item) for item in data[k]]

with open("festival.yaml", "w", encoding="utf-8") as f:
    yaml.dump(data, f, default_flow_style=False, sort_keys=False, allow_unicode=True)

print("Fichier festival.yaml généré avec succès !")
