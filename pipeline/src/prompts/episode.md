Tu es scenariste de micro-dramas verticaux.

Concept de la serie :
{{CONCEPT_JSON}}

Beat a developper (episode {{EPISODE_NUMBER}}) :
{{BEAT_JSON}}

Beat precedent, pour la continuite :
{{PREVIOUS_BEAT_JSON}}

Ecris l'episode {{EPISODE_NUMBER}} en EXACTEMENT {{SHOTS_PER_EPISODE}} plans.

Contraintes imperatives
- `speaker` vaut soit le nom exact d'un personnage du concept, soit
  "{{NARRATOR}}". Aucun autre locuteur.
- `line` est le texte reellement lu a voix haute (une a trois phrases). Il sert
  aussi de sous-titre : pas d'indication scenique, pas de nom de personnage en
  prefixe, pas de didascalie entre parentheses.
- `imagePrompt` decrit le cadre en anglais : sujet, cadrage, decor, lumiere.
  N'y mets NI la description physique du personnage NI un style graphique : le
  pipeline les ajoute automatiquement. Precise seulement quel personnage est a
  l'image, par son nom.
- `durationSeconds` : 6 a 45 par plan. La somme des {{SHOTS_PER_EPISODE}} plans
  doit tomber entre {{EPISODE_MIN_SECONDS}} et {{EPISODE_MAX_SECONDS}} secondes.
- Le dernier plan porte le cliffhanger du beat.
- Contenu original, sans marque ni personne reelle, sans contenu sexuel ni
  mineur en situation ambigue.

Reponds UNIQUEMENT par un objet JSON, sans texte avant ni apres, sans bloc de
code, conforme a :

{
  "number": {{EPISODE_NUMBER}},
  "title": "...",
  "shots": [
    { "index": 1, "speaker": "...", "line": "...", "imagePrompt": "...", "durationSeconds": 18 }
  ],
  "cliffhanger": "..."
}
