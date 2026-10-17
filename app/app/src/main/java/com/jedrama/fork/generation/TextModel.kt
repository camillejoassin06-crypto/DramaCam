package com.jedrama.fork.generation

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Erreur fonctionnelle, avec un message affichable tel quel.
 *
 * `retryable` distingue une sortie invalide ou un reseau coupe (une nouvelle
 * tentative peut reussir) d'une cle refusee ou d'une requete invalide
 * (reessayer ne ferait que repeter â€” et refacturer â€” le meme echec).
 *
 * `outputProblem` marque une reponse mal formee (prose, JSON tronque) : elle se
 * corrige en redemandant tout de suite. Un reseau coupe ou un quota (429) est
 * reessayable aussi, mais plus tard, par WorkManager.
 */
class GenerationException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
    val outputProblem: Boolean = false,
) : Exception(message, cause)

/** Un modele de texte : un prompt en entree, le texte de la reponse en sortie. */
interface TextModel {
    val label: String

    fun complete(prompt: String): String

    /** Appel minimal pour le bouton Â« Tester la cle Â». */
    fun verify(): String = complete("Repondez uniquement par: OK").trim().take(40)
}

/** Fournisseurs proposes dans les Parametres. */
enum class TextProvider { CLAUDE, GEMINI }

/**
 * Claude via le SDK officiel, avec la cle de l'utilisateur.
 *
 * Cout : Claude Haiku 5.5 (defaut) est a 0,10 $ / 0,50 $ par million de tokens,
 * soit quelques centimes pour une serie complete.
 */
class ClaudeTextModel(
    apiKey: String,
    private val model: String,
    private val effort: String,
) : TextModel {

    override val label: String get() = model

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override fun complete(prompt: String): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .addUserMessage(prompt)
            .outputConfig(OutputConfig.builder().effort(effortValue()).build())

        // Sur Opus 5.5 et Sonnet 5.5, un refus des classifieurs de surete est
        // redirige cote serveur vers un modele de repli au lieu d'arreter net.
        // Haiku 5.5 n'a pas de repli serveur.
        if (model in MODELS_WITH_FALLBACK) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val message = try {
            client.messages().create(builder.build())
        } catch (error: UnauthorizedException) {
            throw GenerationException("Cle Claude refusee (401). Verifiez-la dans les Parametres.", false, error)
        } catch (error: RateLimitException) {
            throw GenerationException("Limite de debit Claude (429). Reessai plus tard.", true, error)
        } catch (error: AnthropicIoException) {
            throw GenerationException("Reseau indisponible : ${error.message}", true, error)
        } catch (error: AnthropicServiceException) {
            val status = error.statusCode()
            throw GenerationException("Erreur Claude ($status) : ${error.message}", status >= 500, error)
        }

        val stopReason = message.stopReason().map { it.toString() }.orElse("")
        if (stopReason.contains("max_tokens", ignoreCase = true)) {
            throw GenerationException(
                "Reponse tronquee a $MAX_TOKENS tokens. Baissez l'effort ou le nombre d'episodes.",
                retryable = true,
                outputProblem = true,
            )
        }
        if (stopReason.contains("refusal", ignoreCase = true)) {
            val detail = message.stopDetails().flatMap { it.category() }.map { " ($it)" }.orElse("")
            throw GenerationException("Le modele a refuse de repondre$detail.", retryable = false)
        }

        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null) }
            .joinToString("") { it.text() }
        if (text.isBlank()) {
            throw GenerationException(
                "Reponse vide (motif d'arret : ${stopReason.ifBlank { "inconnu" }}).",
                retryable = true,
                outputProblem = true,
            )
        }
        return text
    }

    private fun effortValue(): OutputConfig.Effort = when (effort.lowercase()) {
        "low" -> OutputConfig.Effort.LOW
        "high" -> OutputConfig.Effort.HIGH
        "xhigh" -> OutputConfig.Effort.XHIGH
        "max" -> OutputConfig.Effort.MAX
        else -> OutputConfig.Effort.MEDIUM
    }

    companion object {
        /** La bible de 50 beats plus la reflexion depassait 16 000 tokens. */
        const val MAX_TOKENS = 32_000L

        const val MODEL_HAIKU = "claude-haiku-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"
        const val MODEL_OPUS = "claude-opus-5-5"

        /** Du moins cher au plus capable : le premier est le defaut. */
        val MODELS = listOf(MODEL_HAIKU, MODEL_SONNET, MODEL_OPUS)

        /** Modeles qui acceptent le repli serveur `fallbacks: "default"`. */
        val MODELS_WITH_FALLBACK = setOf(MODEL_SONNET, MODEL_OPUS)

        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

/**
 * Gemini via l'API REST de Google AI Studio : offre gratuite avec une cle
 * personnelle (projet sans facturation). Les quotas gratuits varient et sont
 * visibles dans la console AI Studio ; un 429 est donc reessaye plus tard.
 */
class GeminiTextModel(
    private val apiKey: String,
    private val model: String,
) : TextModel {

    override val label: String get() = model

    override fun complete(prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        return parseResponse(post(body.toString()))
    }

    override fun verify(): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Repondez uniquement par: OK")))))
        return parseResponse(post(body.toString())).trim().take(40)
    }

    private fun post(payload: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 300_000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
        } catch (error: IOException) {
            throw GenerationException("Reseau indisponible : ${error.message}", true, error)
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status in 200..299 -> return text
                status == 400 && text.contains("API_KEY_INVALID") ->
                    throw GenerationException("Cle Gemini refusee. Verifiez-la dans les Parametres.", false)
                status == 401 ->
                    throw GenerationException("Cle Gemini refusee (401).", false)
                status == 403 ->
                    throw GenerationException("Acces refuse (403) : cle sans droit sur le modele Â« $model Â».", false)
                status == 404 ->
                    throw GenerationException("Modele Gemini Â« $model Â» indisponible pour cette cle. Essayez $DEFAULT_MODEL.", false)
                status == 429 ->
                    throw GenerationException("Quota gratuit Gemini atteint (429). Reprise plus tard.", true)
                status >= 500 ->
                    throw GenerationException("Gemini indisponible ($status).", true)
                else ->
                    throw GenerationException("Erreur Gemini ($status) : ${text.take(200)}", false)
            }
        } catch (error: IOException) {
            throw GenerationException("Reseau indisponible : ${error.message}", true, error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            throw GenerationException("Reponse Gemini illisible", true, it, outputProblem = true)
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GenerationException(
                "Gemini n'a renvoye aucune reponse (${json.optJSONObject("promptFeedback")?.optString("blockReason") ?: "motif inconnu"}).",
                retryable = false,
            )
        val finish = candidate.optString("finishReason")
        if (finish == "MAX_TOKENS") throw GenerationException("Reponse Gemini tronquee.", true, outputProblem = true)
        if (finish in REFUSALS) {
            throw GenerationException("Gemini a refuse de repondre ($finish).", false)
        }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }
        if (text.isBlank()) throw GenerationException("Reponse Gemini vide ($finish).", true, outputProblem = true)
        return text
    }

    companion object {
        private val REFUSALS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

        /**
         * Gratuit dans AI Studio. Les modeles 2.5 sont reserves depuis 2026 aux
         * cles qui les utilisaient deja ; alternative moins gourmande en quota :
         * gemini-3.5-flash-lite. Champ editable dans les Parametres.
         */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}

