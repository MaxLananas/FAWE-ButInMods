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

## Lot 2 — CI, robustesse et performance

Le reste du travail demandé ne relève pas d'un défaut mais d'un coût : la CI
gardait 1,1 Mo d'artefact par exécution (187 artefacts, ~200 Mo, le quota de
stockage du compte) pour un jar que **personne ne téléchargeait**, et elle
passait la moitié de son temps à sauvegarder un cache.

**21. Aucun artefact.** L'étape `upload-artifact` est supprimée et le job
`purge-artifacts` supprime tout ce que le dépôt contient encore (il lui faut
`actions: write`) : le dépôt est à zéro et le reste.

**22. Un seul job au lieu de deux.** La compilation, les tests du moteur, la
vérification du jar et le test rcon partagent un checkout, un JDK et un
répertoire Gradle : plus de second démarrage, plus d'artefact à faire circuler.

**23. Une seule invocation de Gradle.** `build` et `:core:verify` tournaient dans
deux JVM : une phase de configuration, une résolution de dépendances et un
démarrage de Gradle pour rien.

**24. Un cache dont la clé ne change pas.** Le cache de `setup-gradle` a une clé
par exécution : il était donc **sauvegardé** à la fin de chaque job, et cette
sauvegarde prenait 61 s — plus que la compilation qu'elle évitait. Le cache est
maintenant un `actions/cache` sur tout le répertoire Gradle, avec la clé des
fichiers qui décident de l'outillage : écrit une fois, restauré ensuite, jamais
réécrit.

**25. Les tests du moteur sont cachables.** `:core:selfTest` déclare son entrée
(classpath) et une sortie (un tampon) : une poussée qui ne touche pas au moteur
reprend le résultat du cache de build au lieu de rejouer la suite. Il tourne en
plus sur le collecteur série, qui démarre plus vite qu'un collecteur parallèle
pour un processus court.

**26. `LightChecks` alloue son tampon de 4096 cellules à la première cellule.**
Un `FabricWorld` est construit par clic et par tick d'aperçu : chacun portait
8 Ko d'array qu'il n'utilisait presque jamais.

**27. `applyChunk` garde ses tableaux d'états entre deux flushs.** Ils étaient
réalloués à chaque chunk : 1,5 Mo de références par flush de 98 000 blocs.

**28. `applyChunk` passe son chunk à `applyBlockEntity`** au lieu de le
rechercher une fois par bloc à données (une sonde de la table des chunks par
coffre d'une structure collée).

**29. `EditLevel` mémorise les types de paramètres d'une méthode.**
`getParameterTypes()` rend un clone à chaque appel, et un arbre ou une structure
appelle le proxy des milliers de fois par génération.

**30. Trois gardes de plus.** Un type d'entité sans identifiant de registre est
ignoré au lieu de lever ; un item sans identifiant est nommé `minecraft:air` ; un
contour de sélection dont le nombre de segments n'est pas un multiple de six est
dessiné jusqu'où il va ; `//regen` répond air hors de la hauteur de construction
au lieu d'interroger un chunk pour une position qu'il n'a pas.

**31. Un masque ou un motif qui échoue rend un message, plus une « erreur
interne ».** Le refus d'analyse est répond telle quelle, une exception
inattendue est journalisée en erreur (le banc de tests continue donc de compter
les vrais bugs) et le joueur lit `Invalid mask '...' : <raison>`.

### Mesures

| étape | avant | après |
|---|---|---|
| compilation + tests + jar | 100–108 s (2 invocations, cache à clé par run) | 60 s |
| démarrage du serveur | 15–23 s | 15–23 s |
| sauvegarde du cache en fin de job | 61 s | 0 s |
| **job complet** | **2 min 30 s** | **1 min 40 s** |
| artefacts gardés | 187 (≈200 Mo) | 0 |

## Lot 3 — persistance, schémas et historique

Ce lot est la relecture du chemin qui touche au disque : NBT, fichiers de
schéma, fichiers d'historique. Une erreur y est plus grave qu'ailleurs, parce
qu'elle survit au redémarrage.

**32. Le lecteur de varint acceptait un octet de trop.** `readVarInt` lisait
jusqu'à six octets et jetait silencieusement les bits du dernier : un flux
tronqué ou hostile se décodait en un nombre qui n'était pas celui écrit. Il
s'arrête maintenant à cinq octets, la limite du format, et lève au-delà
(`NbtIo.Reader#readVarInt`).

**33. Même défaut pour `readVarLong`, à onze octets.** La limite est dix
(`NbtIo.Reader#readVarLong`).

**34. Un `Boolean` posé dans un compound faisait planter l'écriture.**
`Writer#typeOf` l'annonçait comme `TAG_BYTE`, puis `writePayload` le castait en
`Number` : `ClassCastException` à la sauvegarde. Il s'écrit maintenant comme
l'octet 0 ou 1 qu'il annonce (`NbtIo.Writer#writePayload`).

**35. Le flux décompressé n'était jamais fermé.** `NbtIo.read(...)` ouvrait un
`GZIPInputStream` et le laissait au ramasse-miettes : l'`Inflater` garde de la
mémoire native que seul `close()` rend. Un `try`/`finally` le ferme, sans
toucher au flux de l'appelant (`NbtIo#read(InputStream, boolean, boolean, long)`).

**36. `isGzip` exigeait trois octets pour un magic de deux.** Chaque appelant
qui n'avait lu que l'en-tête devait donc fabriquer un troisième octet
(`new byte[]{head[0], head[1], 0}`) pour se faire comprendre. La fonction lit
les deux octets du magic, et l'appelant lit le fichier tel qu'il est
(`NbtIo#isGzip`, `Schematics#readAny`).

**37. `//schem save` collait une seconde extension.** Enregistrer `house.schem`
au format MCEdit écrivait `house.schem.schematic` — un fichier qu'aucun autre
programme n'ouvre, et que la liste montrait sous un nom à double extension. Le
nom perd d'abord une extension de schéma connue, puis prend celle du format
(`Schematics#serialize`).

**38. La liste des schémas contournait `files.allow-symbolic-links`.**
`entries()` filtrait les liens, `collect()` — celle de `//schem list` et de
`//schem loadall` — non : un lien vers l'extérieur du dossier apparaissait dans
la liste, et le chargement le refusait ensuite. Les deux appliquent la même
règle (`Schematics#collect`).

**39. Une extension en majuscules rendait un schéma invisible.**
`isSchematic` comparait `.schem` à la casse près, alors que `formatOf` et
`resolveExisting` ne la regardent pas : `House.SCHEM`, écrit par un autre
programme ou sur un disque insensible à la casse, n'était jamais listé et ne
pouvait donc pas être chargé par son nom (`Schematics#isSchematic`).

**40. Les fichiers d'historique ne disparaissaient jamais.** La mémoire est
bornée à 128 entrées (`CAPACITY`), mais chaque édition terminée écrivait un
fichier `.snap` et rien n'en supprimait aucun : un serveur occupé remplissait son
disque avec un historique qu'il ne pouvait plus lire. Le dossier garde
`MAX_FILES = 4 × CAPACITY` fichiers, les plus anciens sont supprimés juste après
l'écriture, sur le fil d'écriture (`EditLog#prune`).

**41. `EditLog.load` gonflait tous les fichiers du dossier au démarrage.**
Chaque fichier de l'historique était lu et décompressé, pour n'en garder que
128 ; le temps de démarrage grandissait donc sans fin avec l'âge du serveur. Il
trie et ne lit que les 128 plus récents (`EditLog#historyFiles`).

**42. L'historique triait ses fichiers par nom, donc pas par date.** Le numéro
de séquence est écrit en décimal : « 9 » se compare après « 10 », si bien que
les fichiers de la même milliseconde n'étaient ni chargés ni conservés dans le
bon ordre. Le tri se fait sur l'instant puis sur le numéro relus depuis le nom
(`EditLog#timeInName`, `EditLog#sequenceInName`).

**43. `Snapshots.list` avait le même défaut, en hexadécimal.** Le seizième
snapshot d'une milliseconde (« …-10 ») était listé comme plus ancien que le
quinzième (« …-f »), donc `//snapshot list` et `//snapshot restore <nom>`
prenaient le mauvais fichier. Le tri se fait sur l'instant et la séquence
(`Snapshots#list`, `Snapshots#sequenceOf`).

**44. `//schem save` au format MCEdit allouait sans budget.** Deux tableaux
d'un octet par bloc, pour un volume accepté jusqu'à `2^31 - 8` : 4 Go demandés
d'un coup, donc `OutOfMemoryError` au milieu d'une commande. `Buffers.checkBytes`
refuse la demande avant la première allocation, avec le message habituel
(`Buffers#checkBytes`, `McEditSchematic#write`).

**45. Un `.schematic` chargé sans `allow-ancient-blocks` devenait de l'air sans
un mot.** Le format ne nomme ses blocs que par des identifiants numériques ;
avec le réglage coupé, chaque bloc était remplacé par de l'air et le joueur
voyait un schéma vide, ce qui ressemble à un bug de lecture. Le lecteur compte
ces blocs et prévient, avec le nom du fichier et le réglage à activer
(`McEditSchematic#read`).

## Lot 4 — un chunk ne fait plus tomber l'édition, et l'historique a un budget

**46. Un seul chunk refusé faisait perdre le reste de l'édition.**
`EditSession#flushQueue` vidait la file, puis appliquait les chunks dans une boucle nue :
la première `RuntimeException` — un bloc d'un autre mod qui refuse son état, un chunk que
le jeu n'arrive pas à écrire — sortait de la boucle, et **tous les chunks suivants étaient
abandonnés en silence** alors que l'historique les avait déjà enregistrés : `//undo`
« rendait » des blocs jamais écrits. La boucle isole maintenant chaque chunk, journalise
le couple (x, z) fautif, continue et relance l'échec à la fin, quand tout le reste est
appliqué (`EditSession#flushQueue`).

**47. Deux tables primitives allouées par chunk vidé, pour ne rien y mettre.**
`recordBlockEntities` construisait un `LongObjectMap` et un `LongSet` à chaque chunk,
même quand le tampon ne met aucun bloc à données en file — le cas de `//set`, de toutes
les formes et de tous les brushes. Le chemin courant n'alloue plus rien
(`EditSession#recordBlockEntities`).

**48. `applyBiomes` parcourait les 24 sections du chunk pour en écrire une.**
Un `//setbiome` d'une couche faisait 1 536 lectures — chacune recalculant sa section et son
indice de cellule — pour écrire les 64 cellules réellement touchées. Le flush lit
désormais le tableau de la section concernée, et saute les sections que le tampon ne
remplit pas (`FabricWorld#applyBiomes`).

**49. Un tableau d'un élément alloué à chaque flush.**
Le compteur de positions écrites devait vivre dans un `int[]` pour être capturé par la
lambda du bulk write : une allocation par chunk vidé, sur le chemin de **toute** édition.
C'est un champ de l'adaptateur (`FabricWorld#changedCount`).

**50. Un bloc à données qui refuse son état faisait tomber le chunk entier.**
L'étape 2b (celui dont le bloc change) n'avait aucune garde : la `RuntimeException` sortait
du flush, les blocs étaient écrits mais ni les biomes, ni les données en file, ni la
resynchronisation cliente ne l'étaient — et rien ne disait quelle position était en cause.
La position est maintenant nommée dans le journal, et le reste du chunk est appliqué
(`FabricWorld#applyChunk`, étape 2b).

**51. Même défaut sur les données mises en file.**
L'étape 3b appliquait les NBT de coffres, panneaux et fourneaux sans garde : une donnée
refusée par un bloc d'un autre mod annulait tout ce qui suit dans le chunk. Elle est
isolée par position (`FabricWorld#applyChunk`, étape 3b).

**52. L'historique était borné en nombre, jamais en mémoire.**
`history.size` limite les entrées (20 par défaut) mais pas leur taille : vingt éditions
d'un million de blocs font 80 Mo de lignes, et un joueur qui continue finit en
`OutOfMemoryError` **au milieu d'une édition**. `History` mesure maintenant les tableaux
que chaque entrée garde (`ChangeSet#bytes`, `Record#estimatedBytes`) et supprime les plus
anciennes au-delà d'un quart du tas (`History#trimToBudget`), en gardant toujours la plus
récente pour que `//undo` de l'édition qui vient d'être faite fonctionne.

**53. Un fichier de schéma illisible ne disait pas pourquoi.**
`readAny` essayait les deux dispositions NBT puis répondait « n'est pas un schéma
lisible », en jetant la seule information utile : l'erreur du lecteur. Le message porte
désormais la raison du premier essai — « unexpected end of stream », « NBT nested deeper
than 512 levels » — ce qui distingue un fichier tronqué d'un fichier qui n'est pas un
schéma (`Schematics#readAny`, `Schematics#describe`).

**54. Le brush de terrain construisait une chaîne par bloc.**
`HeightmapBrush#isSnowLayer` comparait `registry.describe(state)` à `minecraft:snow[` :
une chaîne allouée — « minecraft:snow[layers=3] » — pour lire deux caractères, à chaque bloc
d'une colonne parcourue. Les états du bloc neige sont trouvés une fois par brush et le test
est un balayage de quelques entiers, avec la forme textuelle en repli pour un registre qui
ne sait pas énumérer les états (`Brushes#isSnowLayer`).

**55. `//snowsmooth` faisait de même, et compilait une expression régulière par colonne.**
`HeightMaps#snowLayers` analysait la description de chaque bloc avec un motif pour en
extraire le nombre de couches. Les identifiants des états de la neige et leurs couches sont
maintenant lus une fois par registre — un tableau d'entiers, aucune allocation sur le chemin
d'une colonne, et la même repli textuel si le registre ne sait pas énumérer
(`HeightMaps#snowLayers`, `HeightMaps#layersOf`).

## Lot 5 — biomes, arguments et parcours de proche en proche

**56. `//setbiome` résolvait le holder du registre à chaque bloc.**
`FabricWorld#setBiome` appelait `registry.getOrThrow(key)` par position : `EditSession#setBiome`
est appelé pour chaque bloc de la sélection, donc une marche dans le registre (et un `ResourceKey`
reconstruit) 4 096 fois pour les 64 cellules réellement écrites d'une section. Le holder est mis en
table par identifiant, et la table est reconstruite quand un `/reload` renumérote les biomes
(`FabricWorld#biomeHolder`, `FabricRegistries#generation`).

**57. `getBiome` faisait trois recherches par bloc.**
`//copy -b` et `//setbiome` lisent le biome de chaque bloc : `level.getBiome` puis
`biomes.getKey` puis la table nom → id. Le dernier holder et son identifiant sont gardés, ce qui
répond d'une comparaison d'identité le long d'une colonne (`FabricWorld#getBiome`).

**58. Un biome inconnu (`-1`) indexait la table des holders.**
Un `//regen` d'un chunk qu'il n'a pas pu générer, ou un schéma nommant un biome absent du jeu,
rend `-1` ; l'ancien code l'aurait passé tel quel à `getOrThrow` (et la table indexée par lui :
`ArrayIndexOutOfBoundsException`). `biomeHolder` refuse les identifiants négatifs, et
`EditSession#setBiome` ne les enregistre plus dans l'historique (`//undo` n'aurait rien pu rendre).

**59. `/anvil deleteallunvisited` levait une « erreur interne » sur un grand nombre.**
L'argument passe par `matches("\\d+")` puis `Long.parseLong` : « 99999999999999999999 » est fait de
chiffres et ne tient pas dans un `long`, donc `NumberFormatException` sortait de la commande.
L'analyse est gardée et sature à `Long.MAX_VALUE` (`AnvilCommands#unvisitedFilter`).

**60. `//chunk 12,abc` levait une « erreur interne ».**
La paire de coordonnées était lue avec `Double.parseDouble` sans garde. Elle rend maintenant
`Expected two chunk coordinates like 12,-3, got '12,abc'` (`Commands#chunkCoordinate`).

**61. Les brushes de proche en proche allouaient un objet par bloc atteint.**
`//brush splatter`, `//brush shatter` et `//brush layer` gardaient leurs fronts de parcours dans des
`List<Long>` : chaque nœud visité boxait un `Long`. Les trois utilisent `LongQueue`, une file
d'`long` primitifs en anneau (`Operations#splatter`, `Operations#shatter`, `Brushes#layer`).

## Lot 6 — une table qui perdait ses clés, et des arguments qui plantaient

**62. `LongObjectMap.remove` rendait injoignables les clés voisines.**
La table est à adressage ouvert avec sondage linéaire : une lecture avance depuis la case d'accueil
jusqu'à la clé ou une case vide. Retirer une entrée vidait sa case au milieu d'un amas, et toute clé
insérée après elle devenait introuvable — elle restait dans la table, `get` ne la voyait plus. Dans
`EditSession`, c'est un tampon de chunk jeté sous un voisin déjà écrit : ses écritures en attente
disparaissaient sans bruit. Le trou est maintenant refermé en décalant l'amas (`LongObjectMap#closeHole`,
avec la condition de décalage prise dans le sens du sondage), et
`CommandLimitTests#longObjectMapSurvivesRemoval` compare 100 000 `put`/`get`/`remove` à une `HashMap`
sur 400 clés seulement, pour que les amas s'empilent.

**63. Un argument de masque non numérique remontait en exception de parsing.**
`#extrema[a]`, `#surface[abc]`, `#buffer[stone][x]`, `~[stone][deux]`, `#surfaceangle[0][90][x]`,
`#xaxis[abc]`, `#angle[a][b]` appelaient `Integer.parseInt`/`Double.parseDouble` directement : la
commande répondait `Invalid mask '#extrema[a]': For input string: "a"` au lieu de dire quoi corriger,
et `weightedList` laissait sortir la même exception en erreur interne. Tous passent par `intArg`,
`finiteArg` et `Str.isDouble`, avec les bornes vérifiées (`requireRange`) : `#extrema[10][2]`,
`~[stone][5][2]`, `#beside[stone][4][1]` et `{8}{2}` sont refusés au lieu d'être des masques qui ne
correspondent jamais à rien, une taille de buffer nulle est refusée, un poids nul ou négatif aussi.

**64. L'identité de biome gardée en cache survivait à un `/reload`.**
`getBiome` répond l'identifiant du dernier holder lu sans repasser par les registres. Un rechargement
de datapack renumérote les biomes : le holder était le même objet, l'identifiant ne l'était plus, et
`//copy -b` recopiait le biome d'avant le rechargement. Le cache porte maintenant la génération des
registres sous laquelle son identifiant a été lu (`FabricWorld#lastBiomeGeneration`).

**65. `//undo` d'un `//setbiome` comptait les cellules qu'il avait sautées.**
`EditSession#applyBiomeChangeSet` renvoyait le nombre de lignes du record, alors que `setBiome` en
saute deux fois : une cellule déjà à la valeur demandée, et une cellule changée deux fois dans le même
record. Le compte affiché est celui des cellules réellement modifiées.

## Lot 7 — la configuration ne relisait pas ce qu'elle avait écrit

**66. `MiniYaml.write` écrivait les chaînes sans les protéger.**
Le lecteur transforme `12` en entier, `true` en booléen, `[a, b]` en liste, retire les guillemets
d'une valeur entre guillemets et lit une valeur vide comme une section. Une chaîne écrite telle
quelle revenait donc en un autre type : un dossier de schémas nommé `2024` revenait en `Integer`, une
macro nommée `true` en `Boolean`, la chaîne vide en section vide — et une valeur contenant `:` ou un
retour à la ligne cassait la ligne lue. `quoteIfNeeded` met entre guillemets tout ce que le lecteur
lirait autrement, `quote` échappe `\`, `"`, les sauts de ligne et les tabulations, `unquote` les
rétablit, et une liste est écrite élément par élément pour qu'une virgule ne la coupe pas en deux
(`MiniYaml`). Vérifié par `SelfTestMain#miniYamlRoundTripsEveryKindOfValue`.

## Lot 8 — un en-tête de région corrompu, et `*bloc[propriété]`

**67. Un en-tête d'amas négatif faisait sortir `//anvil` en erreur interne.**
`RegionFiles` lit l'index de secteur sur 24 bits puis le multiplie par 4096 : le produit ne tient pas
dans un `int`, donc un en-tête corrompu donnait un décalage négatif, `readInt` levait une
`IndexOutOfBoundsException` hors du `catch` du chunk et la commande entière tombait. Le décalage et la
longueur sont maintenant tenus en `long` contre la taille du fichier, `readInt` refuse un index
négatif, et `inflate` refuse une longueur qui dépasse le fichier : un en-tête qui réclame deux
gigaoctets est ignoré au lieu d'être alloué.

**68. `*oak_log[axis=x]` répondait « bloc inconnu » pour un bloc qui existe.**
Le motif étoile demande tous les états d'un bloc. `statesOf` passait le nom complet à
`Identifier.tryParse`, qui rend `null` dès qu'il y a un crochet, donc un `*bloc[propriété]` — la
syntaxe que WorldEdit accepte — était refusé alors que `*bloc` marchait. La propriété est retirée
avant la lecture du nom, et tous les états du bloc sont rendus (`FabricBlockStateRegistry#statesOf`).
