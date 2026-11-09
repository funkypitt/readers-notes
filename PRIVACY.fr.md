# Politique de confidentialité — Reader's Notes

*Dernière mise à jour : 8 novembre 2026*

Reader's Notes (`com.freedomfighter.readersnotes`) est publiée par Pierre Gallaz. Elle garde sur votre téléphone des notes en texte brut, tapées ou dictées, avec des images et des dessins. Elle est gratuite, sans publicité, sans compte et sans pistage.

## Données collectées par l'application

Aucune. L'application ne collecte, ne conserve ni ne transmet aucune donnée personnelle à son éditeur ni à quiconque, et ne contient aucune bibliothèque d'analyse d'audience, de publicité ou de rapport de plantage.

## Dictée

La dictée n'utilise le microphone que du moment où vous la lancez à celui où vous l'arrêtez ; une notification montre qu'elle est en cours. Le son est gardé sur le téléphone sous forme d'échantillons bruts jusqu'à ce que ses mots soient écrits dans la note, puis effacé. La parole est transformée en texte sur le téléphone lui-même, par le modèle whisper.cpp ; aucun son ni aucun texte ne quitte le téléphone pour cela.

Le modèle de reconnaissance est un fichier téléchargé une fois, à la première dictée, depuis huggingface.co (où sont publiés les modèles de whisper.cpp) ; ce téléchargement est une simple requête de fichier, sans aucun identifiant. Le modèle est partagé, sur le téléphone, avec les autres applications Reader's signées par le même éditeur, pour n'être téléchargé qu'une fois.

## Usage du réseau

En dehors de ce téléchargement, l'application n'utilise le réseau que si vous configurez un dossier de synchronisation : une adresse WebDAV (par exemple votre propre kDrive ou Nextcloud) avec le nom d'utilisateur et le mot de passe d'application que vous saisissez. L'application lit et écrit alors vos notes, en fichiers `.txt`, et leurs images dans ce dossier, et nulle part ailleurs. La synchronisation se fait à l'ouverture de l'application, quand vous quittez une note, et à votre demande. Aucun autre serveur n'est contacté. Le HTTP non chiffré n'est accepté que vers votre propre machine (localhost).

## Données conservées sur le téléphone

Vos notes, leurs images et dessins, vos réglages et les identifiants de synchronisation restent dans l'espace privé de l'application sur le téléphone. Ils sont effacés avec la désinstallation. La sauvegarde Android peut copier les notes et les réglages (pas les images, ni une dictée en attente de ses mots) vers le compte Google configuré sur le téléphone ; cela dépend des réglages de sauvegarde du téléphone, pas de l'application.

L'application peut exporter vos identifiants de synchronisation dans un petit fichier, seulement à votre demande, pour qu'une autre application Reader's du même téléphone utilise le même serveur ; ce fichier va où vous choisissez de l'envoyer. Une note exportée en PDF est enregistrée où vous le choisissez, puis proposée au menu de partage.

## Partage avec d'autres applications

Reader's Launcher, s'il est installé et signé par le même éditeur, peut lire la liste de vos notes pour les montrer dans une tuile. Un texte ou une image partagés vers l'application depuis une autre deviennent une note. Aucune autre application n'a accès à vos notes.

## Autorisations

L'application ne demande que ce qu'il faut à sa tâche : le microphone, pour la dictée ; un service au premier plan avec une notification discrète et un maintien éveillé, pour que la dictée et sa transcription se poursuivent écran éteint ; les notifications, pour les montrer ; le réseau, pour le modèle et la synchronisation facultative.

## Enfants

L'application ne s'adresse pas aux enfants et ne collecte de données de personne.

## Contact

Pierre Gallaz — pierregallaz@gmail.com

Code source : https://github.com/funkypitt/readers-notes
