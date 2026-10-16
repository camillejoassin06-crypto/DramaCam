package com.dramaforge.app.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression : une reponse illisible doit consommer une tentative, pas
 * interrompre la serie.
 *
 * `parseJsonObject` levait une exception non reessayable qui traversait la
 * boucle de tentatives sans etre rattrapee. Resultat : des que le modele
 * repondait en prose — le cas le plus courant — la generation s'arretait, sans
 * les deux regenerations annoncees et sans message.
 */
class RetryClassificationTest {

    private fun parseFailure(raw: String): ScriptGenerator.GenerationException =
        runCatching { ScriptGenerator.parseJsonObject(raw) }
            .exceptionOrNull() as ScriptGenerator.GenerationException

    @Test
    fun `une reponse en prose est reessayable`() {
        val error = parseFailure("Je ne peux pas repondre a cette demande.")
        assertTrue("doit etre reessayable", error.retryable)
    }

    @Test
    fun `un JSON tronque est reessayable`() {
        // Accolades presentes mais contenu casse : c'est org.json qui leve,
        // et cette exception echappait completement a la boucle.
        val error = parseFailure("""{"title": "Le Train", "genres": [}""")
        assertTrue("doit etre reessayable", error.retryable)
        assertTrue(error.message!!.contains("illisible"))
    }

    @Test
    fun `un guillemet non ferme est reessayable`() {
        val error = parseFailure("""{"title": "Le Train}""")
        assertTrue("doit etre reessayable", error.retryable)
    }

    @Test
    fun `le message cite la reponse pour pouvoir diagnostiquer`() {
        val error = parseFailure("Desole, je prefere ne pas ecrire ce scenario.")
        assertTrue(error.message!!.contains("Desole"))
    }

    @Test
    fun `un objet JSON valide ne leve pas`() {
        val json = ScriptGenerator.parseJsonObject("""{"title":"Le Train"}""")
        assertEquals("Le Train", json.getString("title"))
    }

    @Test
    fun `la limite de tokens laisse de la place a une bible de cinquante beats`() {
        // Une bible de 50 beats fait ~6000 tokens, auxquels s'ajoute la
        // reflexion adaptative. Les 16 000 initiaux tronquaient la reponse.
        assertTrue(ScriptGenerator.MAX_TOKENS >= 32_000L)
    }
}
