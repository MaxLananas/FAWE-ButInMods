# Audit des défauts — FAWE-BIM, Minecraft 26.2

Ce fichier est la liste numérotée des défauts trouvés et corrigés. Chaque entrée
donne le fichier, ce qui est faux, ce que ça casse en jeu, et la preuve de la
correction (test du banc `selfTest`, ou vérification de la CI).

La règle du travail est simple : **une entrée = un défaut réel**, pas une
préférence de style. `tools/audit.py` automatise les classes de défauts qui se
relisent mal à la main (caches statiques partagés, `catch` vides, `==` sur des
valeurs encadrées, `toLowerCase()` sans locale, nombres analysés sans garde,
méthodes privées jamais appelées…). Le reste vient d'une lecture du code
comparée aux sources officielles 26.2 (`/tmp/dx/docs/official-262`).

## Lot 1 — 20 défauts corrigés

### Monde Fabric (`FabricWorld`)

**1. `applyBiomes` lisait les biomes du fond du monde pour toutes les sections.**
`LevelChunk#getSectionYFromSectionIndex` rend une coordonnée **de section**,
alors que `ChunkSet#getBiome` attend une coordonnée **de bloc** (elle divise par
16 puis par 4). Résultat : `//setbiome` sur une sélection en y=100 écrivait dans
toutes les sections le biome lu en y=0..12. Corrigé en passant `sectionBaseY =
section << 4`.

**2. `setBiome` écrivait trois cellules sur quatre au mauvais endroit.**
`container.getAndSetUnchecked(x & 3, y & 3, z & 3, …)` : la palette de biomes
d'une section a 4 cellules par axe, atteintes par `(x & 15) >> 2`. `x & 3` ne
donne le même nombre que pour les quatre premiers blocs de chaque axe ; au-delà
il désigne une autre cellule, donc `//setbiome` déplaçait la majorité de ses
biomes.

**3. Le cache de chunk pouvait désigner un chunk déchargé.**
`chunkAt` gardait un `LevelChunk` d'un tick à l'autre. Un chunk déchargé puis
rechargé est un **nouvel** objet : les écritures d'une édition longue (un
`//set` de plusieurs ticks, un coup de brosse après une téléportation)
partaient dans l'ancien objet, donc nulle part, et le flush appliquait un
`ChunkSet` que le chunk vivant n'avait jamais vu. Le cache est maintenant lié
au tick (`level.getGameTime()`).

**4. `supportsCustomRegenSeed()` disait oui quand `generate()` disait non.**
Depuis 26.1 il n'y a plus de graine par niveau : `FabricWorldRegen.generate`
lève `UnsupportedOperationException` si on lui en donne une. Le monde répondait
pourtant `true`, donc `//regen 42` passait le test du noyau, atteignait le refus
et finissait en « erreur interne ». En répondant `false`, le noyau affiche son
message prévu : la graine est ignorée, le terrain vient de la graine du monde.

### Régénération (`FabricWorldRegen`)

**5. `//regen` pouvait figer le serveur.**
`close()` faisait `server.managedBlock(() -> server.getPendingTasksCount() == 0)`
sans limite : une tâche qui ne se termine jamais (file d'un niveau en cours de
fermeture) fait tourner le thread serveur indéfiniment. La boucle est bornée à
30 s, avec un avertissement si le compte n'est pas retombé à zéro.

### Interactions et mixin

**6. Le scroll remettait la hotbar en place *avant* que le paquet l'ait déplacée.**
L'injection était à `HEAD` de `handleSetCarriedItem` ; le corps vanilla fait
`setSelectedSlot(packet.getSlot())` **après**, donc la remise en place était
annulée deux lignes plus loin : un scroll sur un outil lié déplaçait la hotbar
*et* tournait le liant. L'injection mémorise la case à `HEAD` et remet la case
en place à `RETURN`.

**7. Le super-pickaxe lâchait les drops dans un `finally`.**
Le bloc `finally` s'exécutait même si le flush avait échoué : les drops d'un
coffre que le joueur avait encore pouvaient apparaître. La boucle garde
désormais l'échec, ferme l'édition, lâche seulement les blocs que le monde a
réellement perdus, puis relance l'échec.

**8. Les drops du super-pickaxe ignoraient l'outil en main.**
`Block.dropResources(state, level, pos, blockEntity)` tire la table de butin
sans fortune ni toucher de soie : un super-pickaxe de zone sur du minerai de
diamant rendait un diamant quelle que soit la pioche. La variante à six
arguments (entité + `ItemStack`) est utilisée.

**9. La portée des interactions était figée à 4,5 / 5 blocs.**
`reachDistance()` répondait `isCreative() ? 5 : 4.5` au lieu de
`player.blockInteractionRange()`, donc un attribut ou un mod qui change la
portée de interaction était ignoré.

### Lumière et aperçu

**10. `LightTickets` retenait un `ServerLevel` par monde déchargé.**
La table `PENDING` est indexée par le niveau lui-même et n'était jamais
nettoyée : un monde déchargé puis rechargé (multiworld, « quitter vers le
titre ») laissait le `ServerLevel` — chunks, entités, stockage — vivant pour de
bon. L'entrée est retirée quand son dernier chunk est terminé.

**11. L'aperçu de sélection envoyait ~320 paquets de particules deux fois par
seconde, sans changement.** `tick()` appelle `SelectionPreview.refresh` deux
fois par seconde et par joueur ; chaque appel redessinait l'aperçu complet même
si la sélection n'avait pas bougé. Une signature (géométrie + points cliqués +
monde) est mémorisée par joueur, et le dessin est sauté quand elle est
identique.

**12. Cette mémorisation n'était jamais oubliée au départ d'un joueur.**
`SelectionPreview.forget` est appelée sur `DISCONNECT`, comme
`FabricInteractions.forget`.

### Commandes et analyse d'arguments

**13. `//setbiome` oubliait la dernière cellule de biome de chaque colonne.**
La marche partait du bloc le plus bas de la sélection et avançait de 4 :
`//pos1 0,70,0` / `//pos2 3,80,3` atteignait les cellules 68, 72 et 76 et
jamais 80, donc les quatre blocs du haut gardaient leur biome. La marche part
maintenant de la première frontière de cellule (`min.y() - floorMod(min.y(), 4)`).
*Test :* `AuditTests#theTopBiomeCellOfASelectionIsWritten` (« 4 biome cells »).

**14. `#angle` perdait le signe de ses limites.**
`args.replace("[", ",").replace("]", ",").replace("-", "")` retirait **tous**
les moins : `#angle[-45d][45d]` (les surplombs) devenait `#angle[45d][45d]`, et
la moitié de la forme demandée disparaissait sans un mot. Un `-` en tête est
maintenant un signe, un `-` interne reste le séparateur de la forme
`min-max`. *Test :* `AuditTests#aNegativeAngleLimitKeepsItsSign`,
`#aRangeWrittenWithADashIsTwoLimits`.

**15. Les limites en degrés étaient converties par `replace("d", "")`.**
La substitution porte sur toute la chaîne : un nombre en notation
exponentielle ou une limite nommée perdait ses `d` intérieurs. Le suffixe est
maintenant retiré **en fin** de valeur (`Parsers#degrees`), et un nombre
invalide donne le message d'usage au lieu d'une `NumberFormatException`.
*Test :* `AuditTests#aSurfaceAngleIsInDegrees`.

**16. `Parsers.parseDoubleMock` était du code mort public.**
Aucun appelant dans le moteur, aucun test : la seule chose qu'il pouvait faire
était de laisser fuir une `NumberFormatException` brute. Supprimé.

**17. `//setbiome` relisait la sélection pour chaque bloc d'une cellule.**
La marche sur X et Z avançait de 1 et ramenait chaque bloc à sa cellule : 16
appels à `session.setBiome` par cellule, dont 15 sans effet. Les cellules de
l'axe vertical sont maintenant visitées une fois chacune.

### Registres et génération

**18. `/reload` laissait les tables des registres périmées.**
`FabricRegistries.install` n'était appelée qu'au démarrage. Un paquet de données
rechargé reconstruit le `RegistryAccess` du serveur : un biome ajouté n'a plus
d'identifiant, et comme les identifiants sont les noms triés, un biome inséré au
milieu **renumérote** tous les suivants — `//setbiome` nommait alors le mauvais
biome pour la moitié de l'alphabet, et les identifiants écrits dans un
schématique ou un historique pointaient sur leurs voisins. `ensure(...)` compare
l'identité du `RegistryAccess` (ce qu'un rechargement change) et reconstruit les
tables ; elle est appelée là où les tables sont lues (biomes, features,
structures).

**19. Une écriture de générateur qui ne changeait rien vidait le bloc à données.**
`EditLevel#write` enregistrait un bloc à données **neuf** avant de demander
l'écriture ; quand l'édition gardait le bloc déjà en place (`setBlock` rend
`false`), ce bloc neuf — vide — remplaçait à la fermeture les données de celui
du monde : une structure qui écrit un coffre là où un coffre se trouve déjà
vidait le coffre. L'enregistrement n'a lieu que si l'écriture a bien eu lieu.

### Session

**20. Un masque de session plantait quand sa lecture de monde avait disparu.**
`LocalSession.WorldReader` déréférençait `session.getActiveWorld()` sans test
dans `getBlock`, `getBiome`, `minY` et `maxY`. Or le monde actif de la session
est gardé par une **référence faible**, et l'adaptateur construit un
`FabricWorld` par clic : un masque conservé dans la session (`#angle`, une
brosse, un `/tool`) peut donc vivre plus longtemps que le monde pour lequel il a
été analysé — et `#angle` lit `maxY()` **dans son constructeur**, ce qui donnait
un `NullPointerException` sorti d'une ligne de commande au lieu d'un masque.
Le lecteur répond maintenant « rien » (air, biome inconnu, bornes -64/319) quand
il n'a pas de monde.

## Vérification

- `core/src/test/java/…/AuditTests.java` : 5 vérifications (biomes, limites
  d'angle, forme `min-max`, `#surfaceangle` en degrés).
- `tools/audit.py` : l'analyse statique qui a produit la liste de départ.
- CI : les deux jobs (`build` et `runtime`, ce dernier monte un serveur, passe
  `//set` et `//count` en rcon) doivent rester verts.

## Suite

L'audit continue : les lots suivants ajoutent leurs entrées ici, numérotées à
la suite, avec la même forme (défaut, effet, correction, preuve).
