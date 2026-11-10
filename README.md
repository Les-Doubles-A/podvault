# PodVault

Dépôt GitHub : **https://github.com/Les-Doubles-A/podvault**

PodVault est un gestionnaire de podcasts Android **100 % local**. Il lit les
fichiers audio déjà présents sur l’appareil, sans compte, téléchargement réseau,
publicité ni télémétrie. Le manifeste ne demande pas la permission `INTERNET` et
`allowBackup` reste désactivé. Les fichiers audio ne sont jamais déplacés,
modifiés ou supprimés par l’application.

## Démarrage

Ouvrez le dossier du projet dans une version récente d’Android Studio, choisissez
un JDK 17 comme JDK Gradle et laissez Android Studio synchroniser Gradle. Le
projet utilise Android Gradle Plugin 9.2.1, Gradle 9.4.1 ou plus récent, Kotlin
avec le plugin Compose, compileSdk/targetSdk 37 et minSdk 26. Le dépôt ne contient
pas de wrapper Gradle : utilisez la distribution Gradle gérée par Android Studio.
La première synchronisation nécessite l’accès aux dépôts Google et Maven Central.

Pour vérifier le projet depuis Android Studio, lancez les tâches `test` et
`connectedAndroidTest` du module `app`, puis assemblez la variante `release`.
L’application de production active R8 et la réduction des ressources.

## Fonctions

- Scan récursif de dossiers choisis avec le sélecteur de documents Android
  (SAF), reprise des permissions persistantes, progression et gestion des accès
  révoqués. Un scan incomplet ne marque pas les fichiers non observés comme
  manquants ; aucun fichier n’est supprimé automatiquement.
- Lecture des tags audio et de la pochette, repli sur le nom de fichier et
  assainissement des textes extraits.
- Bibliothèque, recherche, favoris, filtres et tris d’épisodes, repérage visuel
  de doublons possibles, lecture en arrière-plan avec Media3 et MediaSession.
- Reprise de lecture avec petit retour en arrière selon la durée de la pause
  (désactivable), vitesse par podcast, sauts configurables, points mémoire
  nommés, minuterie d’arrêt visible et statistiques d’écoute.
- File d’attente : confirmation à l’ajout (avec raccourci), « Lire ensuite » ou
  ajout en fin de file, écran dédié pour réordonner, retirer et vider. Elle vit
  dans le lecteur et n’est pas conservée si le système tue le processus.
- Un épisode arrêté à la fin ou juste avant (3 % de la durée, entre 5 et 30 s)
  est marqué terminé et disparaît de « Continuer l’écoute ».
- Accueil : reprise, « À suivre » (épisode suivant de la série), « Pour vous »
  (score local d’affinité, fraîcheur et diversité, renouvelé chaque jour).
- Indicateur animé sur l’épisode et le podcast en cours de lecture.
- Modifications utilisateur des titres, auteurs et pochettes enregistrées dans
  Room, sans modifier les fichiers sources.
- Scan périodique économe en batterie et sauvegarde JSON manuelle via SAF.
  L’import est versionné et fusionne les données dans la bibliothèque existante.
  Les autorisations SAF ne sont pas transférées par la sauvegarde et doivent
  être accordées à nouveau sur un autre appareil.
- Thème clair/sombre, couleurs dynamiques sur les versions Android compatibles,
  états vides et affichage adaptatif de la grille.

## Architecture et données

Le service locator manuel de `PodVaultApplication` partage Room, DataStore,
les repositories et le contrôleur Media3. Room utilise des migrations explicites
jusqu’au schéma 6 et exporte ses schémas dans `app/schemas`. Il n’y a pas de
`fallbackToDestructiveMigration` : une mise à jour sans migration compatible
échoue au lieu d’effacer la bibliothèque.

Les fichiers audio restent à leur emplacement. La base conserve les épisodes,
favoris, positions, points mémoire et statistiques. Les pochettes extraites sont
en cache local ; les URI SAF et les pochettes choisies peuvent nécessiter un
nouvel accès si le fournisseur retire sa permission.

## Vérifications et limites connues

Le projet contient un test d’instrumentation qui applique les migrations de la
version 1 à la version 6 et vérifie la conservation des données. La compilation,
les tests sur appareil, le parcours complet et l’APK release doivent être
exécutés avec un environnement Android SDK/Gradle fonctionnel.

L’égaliseur/renforcement des voix, les chapitres, les actions de saut
personnalisées dans MediaSession, le widget, les raccourcis d’application,
Paging/FTS, le Baseline Profile et les suites complètes de tests unitaires et
Compose restent à réaliser.

<!-- installeur-release -->
## Installation

Téléchargez **`podvault-0.2.0-alpha.apk`** depuis la [dernière release](https://github.com/Les-Doubles-A/podvault/releases/latest), puis lancez-le.

Copiez l'APK sur le téléphone Android et ouvrez-le (autorisez l'installation depuis des sources inconnues), ou installez-le en USB : `adb install podvault-0.2.0-alpha.apk`.

### Recréer l'installeur

`gradlew assembleDebug` produit l'APK (`app\build\outputs\apk\debug\app-debug.apk`).
