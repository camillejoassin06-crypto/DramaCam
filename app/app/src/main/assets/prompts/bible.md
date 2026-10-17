Tu es showrunner. Voici le concept valide de la serie :

{{CONCEPT_JSON}}

Ecris la bible narrative : EXACTEMENT {{EPISODE_COUNT}} beats, un par episode,
numerotes de 1 a {{EPISODE_COUNT}} dans l'ordre.

Contraintes imperatives
- Un arc complet : accroche aux episodes 1-3, escalades regulieres,
  retournement au deux tiers, resolution au dernier episode.
- Chaque beat finit sur un cliffhanger qui donne envie de swiper. Le dernier
  episode peut resoudre plutot que suspendre.
- Coherence absolue des personnages du concept : pas de nouveau personnage
  principal apres l'episode 5.
- Reste strictement original : aucune reprise d'intrigue, de titre ou de
  replique d'une oeuvre existante.
- `summary` : 20 a 600 caracteres. `cliffhanger` : 10 a 300 caracteres.

Reponds UNIQUEMENT par un objet JSON, sans texte avant ni apres, sans bloc de
code, conforme a :

{
  "seriesTitle": "...",
  "beats": [
    { "episode": 1, "summary": "...", "cliffhanger": "..." }
  ]
}
