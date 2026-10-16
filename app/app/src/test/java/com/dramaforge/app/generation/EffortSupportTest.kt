package com.dramaforge.app.generation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le parametre `effort` renvoie une 400 sur Haiku 4.5. Ce test fige la liste
 * des modeles auxquels il peut etre envoye.
 */
class EffortSupportTest {

    @Test
    fun `haiku n'accepte pas le parametre effort`() {
        assertFalse(ScriptGenerator.MODEL_HAIKU in ScriptGenerator.MODELS_WITH_EFFORT)
    }

    @Test
    fun `opus et sonnet acceptent le parametre effort`() {
        assertTrue(ScriptGenerator.MODEL_OPUS in ScriptGenerator.MODELS_WITH_EFFORT)
        assertTrue(ScriptGenerator.MODEL_SONNET in ScriptGenerator.MODELS_WITH_EFFORT)
    }

    @Test
    fun `tout modele propose a l'utilisateur est connu`() {
        assertTrue(ScriptGenerator.MODELS.containsAll(ScriptGenerator.MODELS_WITH_EFFORT))
    }
}
