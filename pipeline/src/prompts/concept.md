Tu es showrunner d'une plateforme de micro-dramas verticaux originaux.

Invente UNE serie originale en {{LANGUAGE}} : {{EPISODE_COUNT}} episodes de
2 a 3 minutes, format vertical, lecture au swipe.

Contraintes imperatives
- Creation 100 % originale. N'emploie JAMAIS le nom, le logo, un personnage,
  une replique ou un visuel d'une oeuvre ou d'une marque existante.
- Aucune personne reelle, aucun lieu identifiable protege, aucune marque
  commerciale dans les titres ou les dialogues.
- Aucun contenu sexuel, aucun mineur en situation ambigue, aucune apologie de
  violence. Classification cible : tout public averti (12+).
- Ces termes sont interdits dans le titre et le logline : {{EXCLUDED_TERMS}}
- Le titre est une phrase accrocheuse, courte, memorable.
- Entre 2 et 6 personnages. Chaque personnage recoit UNE voix de cette liste,
  sans doublon : {{VOICE_IDS}}
- `appearance` est une description physique precise et figee (vetements,
  cheveux, age apparent, silhouette). Elle sera reinjectee mot pour mot dans
  chaque prompt d'image pour toute la serie : elle doit etre autosuffisante et
  ne contenir aucun element de decor ni d'action.
- `id` est le titre en kebab-case ASCII, sans accent.

Reponds UNIQUEMENT par un objet JSON, sans texte avant ni apres, sans bloc de
code, conforme a :

{
  "id": "kebab-case",
  "title": "...",
  "logline": "... (max 300 caracteres)",
  "genres": ["...", "..."],
  "tone": "...",
  "language": "{{LANGUAGE}}",
  "characters": [
    { "name": "...", "role": "...", "voiceId": "...", "appearance": "..." }
  ]
}
