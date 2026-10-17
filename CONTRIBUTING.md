# Contribution à JeDrama-Fork

Merci de vouloir contribuer ! Ce projet est un fork open source de JeDrama, un studio de micro-dramas verticaux générés par IA sur appareil Android.

## Comment contribuer

1. **Fork** le dépôt sur GitHub
2. **Créez une branche** : `git checkout -b feat/nom-de-la-fonctionnalite`
3. **Faites vos modifications** et testez-les
4. **Commit** avec des messages conventionnels : `feat(app):`, `fix(media):`, `chore(ci):`, etc.
5. **Push** vers votre fork : `git push origin feat/nom-de-la-fonctionnalite`
6. **Ouvrez une Pull Request** vers la branche `main`

## Standards de code

- Langue : français pour les strings utilisateur, anglais pour le code et les commentaires techniques
- Commits : [Conventional Commits](https://www.conventionalcommits.org/)
- Kotlin : style officiel, pas de `var` inutile, null-safety strict
- Tests : chaque nouvelle logique de génération ou de rendu doit avoir des tests unitaires
- FFmpeg : les commandes restent des fonctions pures testables (`FfmpegCommands`)

## Avant de soumettre une PR

- [ ] Les tests passent : `./gradlew testDebugUnitTest`
- [ ] Le build release compile : `./gradlew assembleRelease` (nécessite libffmpeg.so)
- [ ] Les strings sont dans `strings.xml` (pas de texte en dur dans les composables)
- [ ] La licence MIT est respectée (aucun code sous licence restrictive ajouté)

## Signaler un problème

Ouvrez une issue avec :
- Version d'Android et modèle d'appareil
- Étapes pour reproduire
- Comportement attendu vs obtenu
- Logs (logcat) si crash