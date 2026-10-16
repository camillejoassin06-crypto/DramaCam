# Documents périmés — ne pas traiter comme la cible

Ces fichiers décrivent l'architecture **Option B**, abandonnée le 2026-10-16 au
profit de l'Option A (zéro serveur). Ils sont conservés pour mémoire.

Ils contredisent `CLAUDE.md` sur des points structurants :

| Ces documents | Architecture actuelle (CLAUDE.md) |
|---|---|
| Backend Node + Prisma/PostgreSQL | aucun backend |
| Wallet, coins, billing, VIP, parrainage | PERSONAL_MODE, tout est accessible |
| API Anthropic facturée à l'usage | `claude -p` headless (abonnement) |
| URL HLS signées, autorisations serveur | bucket R2 public, catalogue statique |
| Génération quotidienne | 1 série par semaine (fair-use) |
| Publication Play Store | APK sideloadé, 2-3 appareils |

En cas de doute, `CLAUDE.md` fait foi. Le guide d'intégration de l'Option A est
à la racine du dépôt.

## Contenu

- `structure.md` — blueprint initial, bâti sur l'analyse d'un service
  concurrent. L'analyse de ce service n'est plus une référence de conception :
  le produit doit être original.
- `dramaforge-sprint-1..4-*.md` — plans de sprint de l'architecture Option B.
- `prompt.txt` — demande initiale du projet.
