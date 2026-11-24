package com.fuelstride.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Calls NVIDIA NIM (OpenAI-compatible chat completions) with the user's own key.
 * Replies are streamed so a slow answer keeps the connection alive instead of timing out.
 */
object Nim {
    data class Result(val ok: Boolean, val payload: String)

    private const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    // Tested with the user's key: reads food photos in ~25 s, text in ~15 s.
    // (Mistral Large 4 took 3–5+ minutes and rejected reasoning_effort; Kimi timed out; Gemma 3 not available.)
    private const val MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"

    /** Longest silence allowed between streamed pieces (includes time waiting in NVIDIA's queue). */
    private const val READ_GAP_MS = 10 * 60 * 1000

    /**
     * reasoning: "high" lets the model think first (slower, better for workout plans); "none" answers directly.
     * onStage is called with "queued", "thinking" or "writing" as the reply progresses.
     */
    fun ask(
        key: String, prompt: String, imageBase64: String, reasoning: String,
        onStage: (String) -> Unit = {}
    ): Result {
        if (key.isBlank()) return Result(false, "no_key")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (imageBase64.isNotEmpty()) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            )
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            // Covers the model's brief thinking plus the answer; workout plans get more room
            .put("max_tokens", if (reasoning == "high") 16384 else 8192)
            .put("temperature", 0.3)
            .put("stream", true)

        var conn: HttpURLConnection? = null
        return try {
            onStage("queued")
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60_000
                readTimeout = READ_GAP_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                return Result(false, when (code) {
                    401, 403 -> "auth"
                    402 -> "billing"
                    404 -> "model_unavailable"
                    413 -> "image_rejected"
                    422, 400 -> "bad_request"
                    429 -> "rate_limited"
                    504, 408 -> "timeout"
                    else -> "upstream_error"
                })
            }

            val answer = StringBuilder()
            var finish = ""
            var stage = "queued"
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    val delta = choice.optJSONObject("delta")
                    if (delta != null) {
                        val piece = if (delta.isNull("content")) "" else delta.optString("content", "")
                        val thinkingPiece = !delta.isNull("reasoning_content") && delta.optString("reasoning_content", "").isNotEmpty()
                        if (piece.isNotEmpty()) {
                            answer.append(piece)
                            if (stage != "writing") { stage = "writing"; onStage(stage) }
                        } else if (thinkingPiece && stage == "queued") {
                            stage = "thinking"; onStage(stage)
                        }
                    }
                    if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason", "")
                }
            }
            when {
                answer.isNotBlank() -> Result(true, answer.toString())
                finish == "length" -> Result(false, "too_long")
                else -> Result(false, "empty_completion")
            }
        } catch (e: SocketTimeoutException) {
            Result(false, "timeout")
        } catch (e: UnknownHostException) {
            Result(false, "network")
        } catch (e: ConnectException) {
            Result(false, "network")
        } catch (e: IOException) {
            Result(false, "connection_lost")
        } catch (e: Exception) {
            Result(false, "upstream_error")
        } finally {
            conn?.disconnect()
        }
    }
}
