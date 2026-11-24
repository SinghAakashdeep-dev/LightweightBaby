package com.fuelstride.app

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify Web API with Authorization Code + PKCE (no client secret needed).
 * Tokens stay in this app's private storage; the page only sees API responses.
 */
class Spotify(private val prefs: SharedPreferences) {

    companion object {
        const val REDIRECT = "fuelstride://spotify"
        private const val SCOPES = "playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-currently-playing"
        private const val ACCOUNTS = "https://accounts.spotify.com"
        private const val API = "https://api.spotify.com"
    }

    val clientId: String get() = prefs.getString("sp_client", "") ?: ""
    val connected: Boolean get() = !prefs.getString("sp_refresh", "").isNullOrEmpty()

    /** Starts sign-in: returns the URL to open in the browser. */
    fun beginAuth(newClientId: String): String {
        val verifier = randomString(64)
        val state = randomString(16)
        prefs.edit().putString("sp_client", newClientId.trim()).putString("sp_verifier", verifier).putString("sp_state", state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return "$ACCOUNTS/authorize?" + form(
            "client_id" to newClientId.trim(), "response_type" to "code", "redirect_uri" to REDIRECT,
            "code_challenge_method" to "S256", "code_challenge" to challenge, "state" to state, "scope" to SCOPES
        )
    }

    /** Finishes sign-in from the redirect. Returns "ok" or an error code. */
    fun finishAuth(code: String?, state: String?, error: String?): String {
        if (error != null) return if (error == "access_denied") "denied" else "auth_error"
        if (code == null || state == null || state != prefs.getString("sp_state", null)) return "auth_error"
        val r = postForm(
            "$ACCOUNTS/api/token",
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
            "client_id" to clientId, "code_verifier" to (prefs.getString("sp_verifier", "") ?: "")
        ) ?: return "network"
        if (r.first !in 200..299) return "auth_error"
        saveTokens(JSONObject(r.second))
        return "ok"
    }

    fun disconnect() {
        prefs.edit().remove("sp_access").remove("sp_refresh").remove("sp_expires").remove("sp_verifier").remove("sp_state").apply()
    }

    /** Calls the Web API. Returns (status, body); status 0 means no connection, -1 means not signed in. */
    fun call(method: String, path: String, body: String): Pair<Int, String> {
        if (!path.startsWith("/v1/")) return Pair(400, "")
        var token = validToken() ?: return Pair(-1, "")
        var r = request(method, API + path, token, body)
        if (r.first == 401) {
            token = refresh() ?: return Pair(-1, "")
            r = request(method, API + path, token, body)
        }
        return r
    }

    private fun validToken(): String? {
        val access = prefs.getString("sp_access", "") ?: ""
        val expires = prefs.getLong("sp_expires", 0L)
        if (access.isNotEmpty() && System.currentTimeMillis() < expires - 60_000) return access
        return refresh()
    }

    private fun refresh(): String? {
        val rt = prefs.getString("sp_refresh", "") ?: ""
        if (rt.isEmpty()) return null
        val r = postForm("$ACCOUNTS/api/token", "grant_type" to "refresh_token", "refresh_token" to rt, "client_id" to clientId) ?: return null
        if (r.first == 400 || r.first == 401) { disconnect(); return null }
        if (r.first !in 200..299) return null
        return saveTokens(JSONObject(r.second))
    }

    private fun saveTokens(j: JSONObject): String {
        val access = j.getString("access_token")
        val e = prefs.edit()
            .putString("sp_access", access)
            .putLong("sp_expires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        if (j.has("refresh_token")) e.putString("sp_refresh", j.getString("refresh_token"))
        e.apply()
        return access
    }

    private fun request(method: String, url: String, token: String, body: String): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (method == "PUT" || method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (method == "PUT" || method == "POST") conn.outputStream.use { it.write(body.ifEmpty { "{}" }.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (_: IOException) {
            Pair(0, "")
        } finally { conn?.disconnect() }
    }

    private fun postForm(url: String, vararg fields: Pair<String, String>): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(form(*fields).toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Pair(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (_: IOException) { null } finally { conn?.disconnect() }
    }

    private fun form(vararg fields: Pair<String, String>) =
        fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun randomString(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
