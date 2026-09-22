# FAWE-ButInMods

> FastAsyncWorldEdit (FAWE) sous forme de **mod Fabric** complet — pour Minecraft **1.21.10**,
> en solo comme en multijoueur. Aucun plugin Bukkit/Spigot/Paper n'est nécessaire.

Le moteur est un portage fidèle de WorldEdit 7.3.17 + FAWE (voir `NOTICE`), embarqué dans le mod :
sélections, `//set`, `//copy`/`//paste`, brushes, tools, masques, patterns, transformations,
historique (`//undo`/`//redo`), schematics, régénération de chunks, super-pickaxe, etc.

## État actuel

| | |
|---|---|
| Moteur (core) | `core/` — aucune dépendance, testable hors Minecraft (`./gradlew :core:selfTest`) |
| Tests du moteur | **188 tests, 0 échec** (`SelfTestMain`) |
| Commandes enregistrées | **716** (chaque nom de WorldEdit/FAWE est présent : voir `docs/COMMANDS.md`) |
| Implémentées | 136 |
| Alias d'une commande implémentée | 36 |
| Restant à porter | 544 (liste exacte et par section : `docs/STATUS.md`) |
| Noms de l'inventaire WorldEdit+FAWE qui se résolvent | 257 / 259 |

Il reste 544 commandes qui répondent « pas encore porté » en attendant leur implémentation ; la
seule fonctionnalité volontairement absente de la surface est `.s` (ré-exécution du dernier
CraftScript), qui a besoin d'un moteur JavaScript non embarqué.

Les documents générés sont produits par le registre lui-même :

```
./gradlew :core:genDocs     # régénère docs/COMMANDS.md, docs/STATUS.md, docs/commands-spec.json
```

## Compiler

Prérequis : **JDK 21**. Rien d'autre n'est installé à la main (Loom télécharge Minecraft,
les mappings Parchment et Fabric API).

```bash
./gradlew :core:selfTest        # tests du moteur (aucun Minecraft nécessaire)
./gradlew build                 # construit core + le mod Fabric
./gradlew :fabric:runClient     # lance un client de test avec le mod
./gradlew :fabric:runServer     # lance un serveur de test avec le mod
```

Le jar du mod se trouve dans `fabric/build/libs/FAWE-ButInMods-<version>.jar`.

Installation manuelle : déposez le jar **et Fabric API** (`fabric-api-0.136.0+1.21.10` ou plus récent)
dans `.minecraft/mods`, puis lancez la 1.21.10 avec le loader Fabric **0.17.3+**.

## Utilisation en jeu

Comme WorldEdit, avec les deux écritures possibles (Minecraft retire une barre oblique de ce que
vous tapez, donc les deux formes atteignent la même commande) :

```
//wand                # la hache en bois (configurable)
//pos1  //pos2        # ou clic gauche / clic droit avec la hache
//set stone           # ou /set stone
//replace stone,dirt grass_block
//copy  //paste
//brush sphere 5 stone
//sphere 10 glass
/undo  /redo
//schem save maison   //schem load maison
//regen               # régénère le chunk
//tool tree           # lie un outil à l'objet en main
```

Le survol de la sélection est dessiné côté serveur (particules), donc aucune modification du client
n'est nécessaire. Les clics passent par les callbacks Fabric API ; le coup de bras (clic gauche dans
le vide, utilisé par les brushes `shatter`/`erode`) passe par un mixin minimal, exactement comme
l'adaptateur Fabric officiel de WorldEdit.

## Architecture

```
core/     moteur indépendant de la plateforme : régions, masques, patterns, transformations,
          EditSession + file d'attente de chunks, historique, clipboard/schematics, brushes, tools,
          expressions, registre de commandes (toute la surface WorldEdit + FAWE).
fabric/   adaptateur Fabric : entrée du mod, enregistrement Brigadier des 714 commandes, monde
          (lecture/écriture par sections, lumière, entités), pont d'états de blocs/biomes,
          événements de clic, mixin « swing », access widener (2 champs, comme WorldEdit).
docs/     documentation générée depuis le registre (COMMANDS.md, STATUS.md, commands-spec.json)
          et l'inventaire de référence WorldEdit/FAWE (commands-inventory.json).
scripts/  outils de génération (table d'alias).
```

Le cœur ne connaît **aucun** type Minecraft : tout passe par `BlockStateRegistry` et `World`
(implémentés par l'adaptateur Fabric et, pour les tests, par un monde en mémoire). C'est ce qui
permet de faire tourner toute la logique d'édition et la suite de tests sans lancer Minecraft.

## Licence et provenance

GPL-3.0 (voir `LICENSE.txt`, la licence de WorldEdit 7.3.17) ; la provenance et les crédits sont
détaillés dans `NOTICE`. Le code du moteur est un portage comportemental du projet
[WorldEdit](https://github.com/EngineHub/WorldEdit) (7.3.17, la version 1.21.10) et de
[FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit).
