# Changelog

## [Unreleased]

### Ajouté
- Architecture Repository avec interfaces pour testabilité
- Injection de dépendances (Hilt)
- StateFlow pour l'état UI (rafraîchissement automatique)
- Recherche et filtres dans la bibliothèque de séries
- Favoris / watchlist
- Reprise de lecture ("continue watching")
- Cache disque pour les images générées
- Cache bitmap LRU pour le rendu
- Rendu parallèle des plans d'épisode
- CI/CD GitHub Actions (build, test, release)
- Licence MIT, CONTRIBUTING.md, CHANGELOG.md

### Modifié
- Migration du package `com.jedrama.app` → `com.jedrama.fork`
- Strings externalisées pour traduction future
- Proguard mis à jour pour Hilt

### Corrigé
- `DraftsScreen` ne se rafraîchissait pas après génération (ajout de StateFlow)
- Gestion d'état initiale manquante dans `PlayerScreen`
- Durée WAV non vérifiée avant remontage (protection contre fichiers corrompus)

### Optimisé
- Téléchargement d'images avec cache disque (évite les re-téléchargements)
- Bitmap recyclés entre les plans
- FFmpeg : préset `medium` au lieu de `veryfast` en HD pour meilleure qualité/taille

## [1.0.0] - Version initiale (fork de JeDrama)

- Studio de micro-dramas verticaux 100% sur appareil
- Génération concept/bible/script par IA (Claude ou Gemini)
- Voix par synthèse vocale Android (TTS)
- Images par Pollinations ou visuels hors ligne
- Montage FFmpeg embarqué (H.264/AAC, sous-titres WebVTT)
- Lecteur vertical swipe avec reprise
- Bibliothèque locale sans serveur