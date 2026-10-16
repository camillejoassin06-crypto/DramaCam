package com.dramaforge.app.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la logique de generation qui ne depend pas d'Android.
 *
 * L'app ne peut pas etre exercee a la main sur cette machine (pas d'emulateur),
 * donc ces tests sont le seul filet sur le rendu des prompts, l'extraction du
 * JSON et la validation des sorties du modele.
 */
class GenerationLogicTest {

    // --- Rendu des prompts ------------------------------------------------

    @Test
    fun `les jetons sont remplaces`() {
        val rendered = Prompts.renderTemplate(
            "Serie de {{EPISODE_COUNT}} episodes en {{LANGUAGE}}.",
            mapOf("EPISODE_COUNT" to 50, "LANGUAGE" to "fr"),
        )
        assertEquals("Serie de 50 episodes en fr.", rendered)
    }

    @Test
    fun `un jeton repete est remplace partout`() {
        val rendered = Prompts.renderTemplate(
            "{{N}} puis {{N}}",
            mapOf("N" to 3),
        )
        assertEquals("3 puis 3", rendered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un jeton non resolu fait echouer le rendu`() {
        // Preferable a l'envoi d'un prompt contenant un litteral {{MANQUANT}} :
        // le modele repondrait quelque chose, mais pas ce qu'on croit demander.
        Prompts.renderTemplate("Il manque {{MANQUANT}}.", mapOf("AUTRE" to 1))
    }

    // --- Extraction du JSON -----------------------------------------------

    @Test
    fun `un objet JSON nu est lu`() {
        val json = ScriptGenerator.parseJsonObject("""{"title":"Le Train"}""")
        assertEquals("Le Train", json.getString("title"))
    }

    @Test
    fun `un bloc de code est retire`() {
        val json = ScriptGenerator.parseJsonObject("```json\n{\"title\":\"Le Train\"}\n```")
        assertEquals("Le Train", json.getString("title"))
    }

    @Test
    fun `un preambule bavard est ignore`() {
        val json = ScriptGenerator.parseJsonObject(
            "Voici le concept demande :\n{\"title\":\"Le Train\"}\nBonne lecture.",
        )
        assertEquals("Le Train", json.getString("title"))
    }

    @Test(expected = ScriptGenerator.GenerationException::class)
    fun `une reponse sans JSON leve une erreur explicite`() {
        ScriptGenerator.parseJsonObject("Je ne peux pas repondre a cette demande.")
    }

    // --- Validation du concept --------------------------------------------

    private fun character(name: String, voice: String) = Character().apply {
        this.name = name
        role = "Role de $name"
        voiceId = voice
        appearance = "description physique suffisamment longue pour passer la validation"
    }

    private fun concept() = Concept().apply {
        id = "le-train-de-minuit"
        title = "Le Train de Minuit"
        logline = "Chaque nuit un passager de plus connait mon nom."
        genres = listOf("Mystere")
        tone = "Tension feutree"
        language = "fr"
        characters = listOf(
            character("Alma", "fr-FR-DeniseNeural"),
            character("Victor", "fr-FR-HenriNeural"),
        )
    }

    @Test
    fun `un concept correct est valide`() {
        assertTrue(concept().validate().messages.toString(), concept().validate().isValid)
    }

    @Test
    fun `un identifiant non kebab-case est refuse`() {
        val problems = concept().apply { id = "Le Train" }.validate()
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("kebab"))
    }

    @Test
    fun `deux personnages ne peuvent pas partager une voix`() {
        val problems = concept().apply {
            characters = listOf(
                character("Alma", "fr-FR-DeniseNeural"),
                character("Victor", "fr-FR-DeniseNeural"),
            )
        }.validate()
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("voix"))
    }

    @Test
    fun `un seul personnage est refuse`() {
        val problems = concept().apply {
            characters = listOf(character("Alma", "fr-FR-DeniseNeural"))
        }.validate()
        assertFalse(problems.isValid)
    }

    // --- Validation de la bible -------------------------------------------

    private fun bible(count: Int, renumber: (Int) -> Int = { it }) = Bible().apply {
        seriesTitle = "Le Train de Minuit"
        beats = (1..count).map { index ->
            Beat().apply {
                episode = renumber(index)
                summary = "Resume de l'episode $index, assez long pour etre accepte."
                cliffhanger = "Un nom de plus est barre."
            }
        }
    }

    @Test
    fun `une bible contigue est valide`() {
        assertTrue(bible(50).validate(50).isValid)
    }

    @Test
    fun `un nombre de beats errone est refuse`() {
        assertFalse(bible(49).validate(50).isValid)
    }

    @Test
    fun `des beats non contigus sont refuses`() {
        val problems = bible(5) { if (it == 3) 7 else it }.validate(5)
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("contigus"))
    }

    // --- Validation d'un episode ------------------------------------------

    private fun script(
        number: Int = 1,
        shotCount: Int = GenerationLimits.SHOTS_PER_EPISODE,
        perShotSeconds: Double = 20.0,
        speakerAt: (Int) -> String = { if (it % 2 == 0) "Alma" else GenerationLimits.NARRATOR },
    ) = EpisodeScript().apply {
        this.number = number
        title = "La voiture sans numero"
        cliffhanger = "La porte se referme sur un siege deja occupe."
        shots = (1..shotCount).map { index ->
            Shot().apply {
                this.index = index
                speaker = speakerAt(index)
                line = "Replique du plan $index."
                imagePrompt = "night train platform, shot $index, cold light"
                durationSeconds = perShotSeconds
            }
        }
    }

    @Test
    fun `un episode correct est valide`() {
        val problems = script().validate(concept(), 1)
        assertTrue(problems.toString(), problems.isValid)
    }

    @Test
    fun `un nombre de plans errone est refuse`() {
        val problems = script(shotCount = 6).validate(concept(), 1)
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("plans"))
    }

    @Test
    fun `une duree totale trop courte est refusee`() {
        // 8 plans de 5 s = 40 s, bien en dessous de la cible de 2 minutes.
        val problems = script(perShotSeconds = 5.0).validate(concept(), 1)
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("hors cible"))
    }

    @Test
    fun `une duree totale trop longue est refusee`() {
        val problems = script(perShotSeconds = 40.0).validate(concept(), 1)
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("hors cible"))
    }

    @Test
    fun `un locuteur absent du concept est refuse`() {
        val problems = script(speakerAt = { "Inconnu" }).validate(concept(), 1)
        assertFalse(problems.isValid)
        assertTrue(problems.toString().contains("locuteurs inconnus"))
    }

    @Test
    fun `le narrateur est un locuteur valide`() {
        val problems = script(speakerAt = { GenerationLimits.NARRATOR }).validate(concept(), 1)
        assertTrue(problems.toString(), problems.isValid)
    }

    @Test
    fun `un numero d'episode inattendu est refuse`() {
        assertFalse(script(number = 4).validate(concept(), 1).isValid)
    }

    // --- Verdict de surete -------------------------------------------------

    @Test
    fun `un verdict favorable ne bloque rien`() {
        val verdict = QualityVerdict().apply {
            originalContent = true
            characterConsistency = true
            safeForTargetRating = true
        }
        assertTrue(verdict.blockers().isEmpty())
    }

    @Test
    fun `chaque critere en echec est signale`() {
        val verdict = QualityVerdict().apply {
            originalContent = false
            characterConsistency = true
            safeForTargetRating = false
        }
        assertEquals(listOf("originalite", "surete du contenu"), verdict.blockers())
    }

    @Test
    fun `un verdict par defaut bloque`() {
        // Les booleens valent false par defaut : une reponse tronquee ou un
        // champ manquant doit bloquer, jamais passer.
        assertEquals(3, QualityVerdict().blockers().size)
    }
}
