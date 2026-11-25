-- Comptes locaux : un mot de passe, un lien à usage unique pour le choisir, un
-- code reçu par mail pour finir la connexion.
--
-- Rien n'est stocké en clair. Le mot de passe est un hachage bcrypt ; le lien
-- et le défi de connexion sont des SHA-256 de valeurs de 256 bits tirées au
-- hasard (inutile de les saler : on ne les devine pas) ; le code à six chiffres
-- est salé par le défi, dont seul le hachage est ici. Les tables sont globales,
-- comme `compte` : une personne sert toutes les éditions.
ALTER TABLE compte ADD COLUMN mot_de_passe_hash VARCHAR(255);
ALTER TABLE compte ADD COLUMN mot_de_passe_le TIMESTAMP WITH TIME ZONE;

-- Le lien d'invitation ou de réinitialisation. Un seul en vigueur par compte :
-- en émettre un autre remplace le précédent. Il se consomme en le supprimant.
CREATE TABLE compte_jeton (
    jeton_hash VARCHAR(64) PRIMARY KEY,
    compte_id VARCHAR(64) NOT NULL REFERENCES compte (id),
    objet VARCHAR(16) NOT NULL CHECK (objet IN ('INVITATION', 'REINITIALISATION')),
    expire_le TIMESTAMP WITH TIME ZONE NOT NULL,
    cree_le TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX compte_jeton_compte ON compte_jeton (compte_id);

-- Le défi : ce que la saisie du bon mot de passe donne en échange, pour finir
-- la connexion. Sans seconde étape, il suffit ; avec elle, il faut aussi le code
-- (code_hash non nul). Un seul par compte, à usage unique, quelques essais.
CREATE TABLE compte_defi (
    defi_hash VARCHAR(64) PRIMARY KEY,
    compte_id VARCHAR(64) NOT NULL REFERENCES compte (id),
    code_hash VARCHAR(64),
    tentatives_restantes INTEGER NOT NULL,
    expire_le TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX compte_defi_compte ON compte_defi (compte_id);
