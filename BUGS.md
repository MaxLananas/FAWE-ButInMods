# Audit des défauts — FAWE-BIM, Minecraft 1.21.10

Ce fichier est la liste numérotée des défauts trouvés et corrigés sur la ligne
1.21.10. Chaque entrée donne le fichier, ce qui est faux, ce que ça casse en jeu,
et la preuve de la correction (test du banc `selfTest`, ou passage de la CI).

La règle du travail est simple : **une entrée = un défaut réel**, pas une
préférence de style. `tools/audit.py` automatise les classes de défauts qui se
relisent mal à la main (caches statiques partagés, `catch` vides, `==` sur des
valeurs encadrées, `toLowerCase()` sans locale, nombres analysés sans garde,
méthodes privées jamais appelées…). Le reste vient de la comparaison du port
1.21.10 avec la ligne 26.2, qui a déjà passé ces lots d'audit, et de la lecture
du code.

## Moteur (le port ne depend pas de Minecraft)

### NBT et schémas

**1. `NbtIo.isGzip` lisait le nombre magique comme « plus de deux octets ».**
Un en-tête lu exactement — deux octets — n'était pas un fichier gzip, et le
lecteur de schéma contournait le bug en rajoutant un octet au sien. Corrigé en
`>= 2`, et le contournement supprimé.

**2. `NbtIo.read` ne fermait jamais l'inflation qu'il ouvrait.**
Le `GZIPInputStream` garde de la mémoire native que seul `close()` rend : chaque
lecture d'un fichier gzippé fuyait, et une longue session de serveur
s'accumulait.

**3. Le lecteur de varint lisait un sixième octet et perdait ses bits.**
Un flux tronqué ou hostile était décodé en un nombre qui n'était pas celui
écrit. La boucle s'arrête à cinq octets, la limite du format.

**4. Un schematic MCEdit allouait deux tableaux à la taille du volume.**
Un gros presse-papiers finissait en `OutOfMemoryError` au lieu d'une réponse.
`Buffers.checkBytes` vérifie la taille contre le budget de travail avant la
moindre allocation.

**5. L'extension d'un schematic était comparée avec la casse.**
Un fichier écrit `House.SCHEM` par un autre programme était invisible à la
liste, qui est le seul moyen de le trouver.

**6. `//schem save house.schem mcedit` écrivait `house.schem.schematic`.**
Un fichier que rien d'autre n'ouvre. Le nom garde son tronc.

**7. La liste des schematics suivait un lien symbolique hors du dossier.**
`entries()` applique la même politique : un lien n'est pas un schematic du
dossier, et la liste en nommait un que toutes les autres commandes refusaient
ensuite de charger.

### Historique et instantanés

**8. Le dossier d'historique n'était jamais purgé.**
Chaque édition finie écrivait un fichier et rien n'en supprimait : un serveur
_actif_ remplissait son disque d'un historique qu'il ne pouvait plus lire, et
un redémarrage relisait tout.

**9. Les fichiers d'historique étaient triés par leur nom, comme du texte.**
Le numéro de séquence est écrit en texte, où « f » vient après « 10 » : le
seizième fichier d'une milliseconde était lu comme le plus ancien. Le tri se
fait sur le moment puis sur le numéro.

**10. Les instantanés étaient triés de la même façon.**
La liste des snapshots d'un dossier comparait les noms, donc le seizième
snapshot d'une milliseotide était listé avant le quinzième.

### Commandes

**11. Les parseurs de motif et de masque avalaient toutes les exceptions.**
Une erreur de syntaxe est répondue en chat et rien de plus ; mais un bug dans
un parseur — un `null`, un cast qui ne tient pas — disparaissait aussi. Le
message d'une exception sans texte disait « null », ce qui est pire que de
nommer le genre d'erreur.

**12. `#angle` et `#surfaceangle` perdaient le signe d'une limite négative.**
Le parseur retirait tous les `-` de la chaîne : `#angle[-45d][45d]` sélectionnait
exactement ce que sélectionnait `#angle[45d][45d]`. Un `-` après le premier
caractère sépare les deux nombres ; un `-` initial est un signe.

**13. `//setbiome` marchait les cellules depuis le bloc le plus bas.**
La cellule de biome couvre 4×4×4 blocs : une sélection de y=70 à y=80
contient les cellules 68, 72, 76 et 80, et la marche depuis 70 atteignait 68,
72 et 76. Le haut de chaque colonne gardait son biome.

**14. Une session dont le monde avait disparu levait une NullPointerException.**
Le monde d'une session est tenu faiblement, et l'adaptateur en crée un par
clic : un masque ou un motif (`#angle`, une brosse, `/tool`) peut vivre plus
longtemps que le monde pour lequel il a été analysé.

**15. `Parsers.parseDoubleMock` était du code de test dans la production.**

### Adaptateur Fabric

**16. `applyBiomes` lisait les biomes du fond du monde pour toutes les sections.**
`LevelChunk#getSectionYFromSectionIndex` rend une coordonnée **de section**,
alors que `ChunkSet#getBiome` attend une coordonnée **de bloc**. `//setbiome`
écrivait dans les 24 sections le biome lu en y=0..12. Corrigé en
`sectionBaseY = section << 4`.

**17. `setBiome` écrivait trois cellules sur quatre au mauvais endroit.**
La palette de biomes d'une section a 4 cellules par axe, atteintes par
`(x & 15) >> 2`. `x & 3` ne donne le même nombre que pour les quatre premiers
blocs de chaque axe ; au-delà il désigne une autre cellule.

**18. Le cache de chunk pouvait désigner un chunk déchargé.**
Un chunk déchargé puis rechargé est un **nouvel** objet : les écritures d'une
édition longue partaient dans l'ancien objet, donc nulle part. Le cache est
maintenant lié au tick (`level.getGameTime()`).

**19. Les identifiants de biome n'étaient lus qu'au démarrage.**
`/reload` (ou un pack de données ajouté à chaud) remplace le `RegistryAccess` du
serveur : un biome ajouté au milieu renumérote tous les suivants, puisque les
identifiants sont les noms triés. `//setbiome` nommait alors le mauvais biome
pour la moitié de l'alphabet, et les identifiants écrits dans un schematic ou
un fichier d'historique pointaient sur leurs voisins.

**20. Le super-pickaxe lâchait les drops dans un `finally`.**
Le bloc s'exécutait même si le flush avait échoué : les drops d'un coffre que le
joueur avait encore pouvaient apparaître. La boucle garde l'échec, ferme
l'édition, lâche seulement les blocs que le monde a réellement perdus, puis
relance l'échec.

**21. Les drops du super-pickaxe ignoraient l'outil en main.**
L'appel sans joueur ni outil tirait la table de butin simple : un super-pickaxe
sur du minerai de diamant donnait un diamant, quelle que soit la pioche.

**22. La portée était une paire de nombres fixes.**
Un pack de données ou un mod qui bouge la portée d'interaction était ignoré.
Le jeu répond maintenant lui-même.

**23. Le scroll remettait la hotbar en place _avant_ que le paquet l'ait déplacée.**
L'injection était à `HEAD` de `handleSetCarriedItem`, et le corps vanilla fait
`setSelectedSlot(packet.getSlot())` **après** : la remise en place était annulée
deux lignes plus loin. Un scroll sur un outil lié déplaçait la hotbar *et*
tournait le liant. L'injection mémorise la case à `HEAD` et la remet à `RETURN`.

**24. Un générateur vidait un coffre déjà là.**
Le bloc était écrit _après_ son entité de bloc : un générateur qui écrivait un
coffre là où un coffre se trouvait déjà remettait une entité vide, et le coffre
était vidé à la fermeture du niveau.

### Le registre des blocs

**29. Un nom de bloc inconnu valait de l'air.**
`BuiltInRegistries.BLOCK.getValue(id)` répond la valeur par défaut du registre
pour une clé qu'il ne détient pas — de l'air pour les blocs — et le test
`== null` n'était jamais vrai : `//set notablock` remplissait la sélection
d'air, et `//replace stone notablock` effaçait la pierre au lieu de refuser le
nom. La clé de ce qui revient est ce qui distingue un nom inconnu de la
valeur par défaut.

Le même défaut touchait les features, les placed features, les structures et
les items : `//feature pasunefeature` plaçait la feature par défaut au lieu
d'être refusé, et un item qui n'existe pas donnait au joueur une pile vide.

**30. `//count` déclarait son masque comme obligatoire.**
Dans WorldEdit et FAWE, le masque est facultatif : `//count` seul compte tous
les blocs de la sélection. Ici il était refusé avec une ligne d'usage, et
`//count` ne comptait rien.

**31. `//gmask clear` ne vidait pas le masque.**
Le mot était analysé comme le nom d'un masque : `//gmask clear` installait un
masque qui ne correspondait à rien au lieu de vider le masque global. Comme
dans WorldEdit, le mot est une commande à part entière — `//smask clear` pour
le masque source des brosses.

**32. L'attente d'une régénération n'était pas bornée.**
Le niveau temporaire vidait la file des tâches du serveur dans une boucle sans
limite : une tâche en attente pour toujours la faisait tourner sur le fil du
serveur et gelait le serveur au milieu d'un `//regen`. L'attente est bornée à
trente secondes, et la file qui est toujours occupée est signalée.

**33. Une entité d'un type inconnu vidait la copie sur une exception.**
Le type n'a pas d'identifiant de registre à écrire, donc ses données ne
pouvaient pas être relues dans un monde : la clé était déréférencée sans
vérification. L'entité est laissée hors de la copie.

### Optimisations

**25. Les états remplacés par un flush étaient alloués à chaque flush.**
Une grosse édition flush une chunk après l'autre et allouait deux tableaux de
98k références à chaque fois : un mégaoctet et demi de garbage par chunk. Ils
sont gardés et grandissent comme les positions.

**26. Le tableau des cellules de lumière était alloué pour chaque adaptateur.**
Un objet adaptateur est construit par clic et par tick de sélection : chacun
portait ces huit kilooctets, qu'il ait ou non flushé une chunk.

**27. Les types de paramètres d'une méthode du niveau étaient relus à chaque appel.**
`getParameterTypes` répond un clone neuf ; un arbre ou une structure appelle
cette méthode des milliers de fois par génération.

**28. Les particules de la sélection partaient deux fois par seconde.**
Un joueur immobile voyait les mêmes trois cents paquets de particules repartir
sans arrêt. Le dessin n'a lieu que quand la sélection a changé, et la
signature d'un joueur parti est oubliée.

## Vérification

- `./gradlew :core:verify` — le banc d'essai du moteur (`AuditTests` couvre les
  défauts 11, 12 et 13, et les autres ont leur test dans la suite).
- `tools/audit.py` — l'audit statique des classes de défauts citées en tête.
- `tools/rcon_matrix.py` — la matrice de commandes conduite sur le serveur qui
  tourne : chaque commande enregistrée par le mod, ses combinaisons, ses
  refus, et les comptes exacts (`//count` sur une sélection de 16×16×16 doit
  répondre 4096).
