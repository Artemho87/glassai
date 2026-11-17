package com.artem.glassai

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Запросы к ИИ: OpenRouter, Claude (Anthropic API) или Gemini (Google AI Studio API). */
object AiClient {

  /** Одна прошлая реплика разговора: вопрос и ответ */
  data class Turn(val user: String, val assistant: String)

  /**
   * @param history прошлые реплики (чтобы ИИ помнил разговор)
   * @param audio звук (aac или wav) — для диктофона и переводчика (Claude звук не понимает)
   */
  suspend fun ask(
      system: String,
      text: String,
      image: Bitmap? = null,
      maxTokens: Int = 400,
      history: List<Turn> = emptyList(),
      audio: ByteArray? = null,
      audioFormat: String = "aac",
  ): String =
      withContext(Dispatchers.IO) {
        val jpeg = image?.let { toBase64Jpeg(it) }
        val b64 = audio?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        when (Prefs.provider) {
          "gemini" -> gemini(system, text, jpeg, maxTokens, history, b64, audioFormat)
          "claude" -> {
            if (b64 != null) {
              error("Claude не умеет слушать звук. Выбери OpenRouter или Gemini в «Настройках ИИ» " +
                  "или в переводчике переключи «Как слушать» на Google")
            }
            claude(system, text, jpeg, maxTokens, history)
          }
          else -> openrouter(system, text, jpeg, maxTokens, history, b64, audioFormat)
        }
      }

  suspend fun translate(text: String, fromCode: String, toCode: String): String {
    val system =
        "Ты синхронный переводчик живого разговора. Переведи фразу с языка «${Langs.name(fromCode)}» " +
            "на язык «${Langs.name(toCode)}». Выведи только перевод, естественным разговорным языком, " +
            "без кавычек, пояснений и транслитерации. Если фраза уже на нужном языке — просто повтори её."
    return ask(system, text, null, 300)
  }

  /** Результат умного перевода */
  data class Tr(val lang: String, val text: String, val translation: String, val translit: String)

  /**
   * Перевод живого разговора: сам определяет язык фразы и направление перевода.
   * Принимает либо распознанный текст, либо звук фразы (WAV).
   */
  suspend fun smartTranslate(
      text: String?,
      wav: ByteArray?,
      mine: String,
      theirs: List<String>,
      lastTheir: String,
      hint: String?,
      context: List<String>,
  ): Tr {
    val langs = (listOf(mine) + theirs).distinct()
    val list = langs.joinToString(", ") { "«${Langs.name(it)}» ($it)" }
    val theirNames = theirs.joinToString(" или ") { "«${Langs.name(it)}»" }
    val ctx =
        if (context.isEmpty()) ""
        else "\nПредыдущие реплики разговора (только для контекста, их не переводи):\n" +
            context.takeLast(6).joinToString("\n")
    val system =
        "Ты синхронный переводчик живого разговора.\n" +
            "Мой язык: «${Langs.name(mine)}» ($mine). Язык собеседника: $theirNames.\n" +
            "Определи, на каком языке сказана фраза (один из: $list).\n" +
            "Если она на языке собеседника — переведи на «${Langs.name(mine)}».\n" +
            "Если она на моём языке — переведи на «${Langs.name(lastTheir)}».\n" +
            "Переводи естественно и разговорно, сохраняй смысл, тон и вежливость; имена и названия " +
            "не переводи; ничего не добавляй от себя и не отвечай на саму фразу.$ctx\n" +
            "Ответь строго одной строкой JSON без markdown:\n" +
            "{\"lang\":\"код языка фразы\",\"text\":\"фраза в оригинале\",\"translation\":\"перевод\"," +
            "\"translit\":\"произношение перевода латиницей, только если перевод сделан на язык собеседника " +
            "с нелатинским письмом, иначе пустая строка\"}\n" +
            "Если речи нет, это шум или ничего не понятно — верни все поля пустыми."
    val user =
        if (wav != null) "Переведи фразу из аудио."
        else "Фраза: $text" + (hint?.let { "\n(распознаватель считает, что язык: $it)" } ?: "")
    val raw = ask(system, user, null, 500, audio = wav, audioFormat = "wav")
    val s = raw.indexOf('{')
    val e = raw.lastIndexOf('}')
    if (s >= 0 && e > s) {
      val o = runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrNull()
      if (o != null) {
        return Tr(o.optString("lang"), o.optString("text"), o.optString("translation"), o.optString("translit"))
      }
    }
    return Tr(theirs.first(), text ?: "", raw.trim(), "")
  }

  // ---------- OpenRouter (формат OpenAI) ----------
  private fun openrouter(
      system: String,
      text: String,
      jpeg: String?,
      maxTokens: Int,
      history: List<Turn>,
      audio: String?,
      audioFormat: String,
  ): String {
    val key = Prefs.openrouterKey
    require(key.isNotBlank()) { "Не указан ключ OpenRouter (раздел «Настройки ИИ»)" }
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
    history.forEach {
      messages.put(JSONObject().put("role", "user").put("content", it.user))
      messages.put(JSONObject().put("role", "assistant").put("content", it.assistant))
    }
    val userContent = JSONArray()
    userContent.put(JSONObject().put("type", "text").put("text", text))
    if (jpeg != null) {
      userContent.put(
          JSONObject()
              .put("type", "image_url")
              .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpeg")),
      )
    }
    if (audio != null) {
      userContent.put(
          JSONObject()
              .put("type", "input_audio")
              .put("input_audio", JSONObject().put("data", audio).put("format", audioFormat)),
      )
    }
    messages.put(JSONObject().put("role", "user").put("content", userContent))
    val body =
        JSONObject()
            .put("model", Prefs.openrouterModel)
            // запас на «размышления» у думающих моделей, сами размышления не возвращаем
            .put("max_tokens", maxTokens * 4)
            .put("reasoning", JSONObject().put("effort", "low").put("exclude", true))
            .put("messages", messages)
    val json =
        post(
            "https://openrouter.ai/api/v1/chat/completions",
            mapOf(
                "Authorization" to "Bearer $key",
                "HTTP-Referer" to "https://github.com/Artemho87/glassai",
                "X-Title" to "GlassAI",
            ),
            body.toString(),
        )
    val choices = json.optJSONArray("choices")
    if (choices == null || choices.length() == 0) error("OpenRouter не дал ответа")
    val msg = choices.getJSONObject(0).optJSONObject("message")
    return (msg?.optString("content") ?: "").trim().ifBlank { error("OpenRouter вернул пустой ответ") }
  }

  // ---------- Claude ----------
  private fun claude(system: String, text: String, jpeg: String?, maxTokens: Int, history: List<Turn>): String {
    val key = Prefs.claudeKey
    require(key.isNotBlank()) { "Не указан ключ Claude API (раздел «Настройки ИИ»)" }
    val messages = JSONArray()
    history.forEach {
      messages.put(JSONObject().put("role", "user").put("content", it.user))
      messages.put(JSONObject().put("role", "assistant").put("content", it.assistant))
    }
    val content = JSONArray()
    if (jpeg != null) {
      content.put(
          JSONObject()
              .put("type", "image")
              .put(
                  "source",
                  JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpeg),
              ),
      )
    }
    content.put(JSONObject().put("type", "text").put("text", text))
    messages.put(JSONObject().put("role", "user").put("content", content))
    val body =
        JSONObject()
            .put("model", Prefs.claudeModel)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)

    val json =
        post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
    val parts = json.getJSONArray("content")
    val sb = StringBuilder()
    for (i in 0 until parts.length()) {
      val p = parts.getJSONObject(i)
      if (p.optString("type") == "text") sb.append(p.optString("text"))
    }
    return sb.toString().trim()
  }

  // ---------- Gemini ----------
  private fun gemini(
      system: String,
      text: String,
      jpeg: String?,
      maxTokens: Int,
      history: List<Turn>,
      audio: String?,
      audioFormat: String,
  ): String {
    val key = Prefs.geminiKey
    require(key.isNotBlank()) { "Не указан ключ Gemini API (раздел «Настройки ИИ»)" }
    val contents = JSONArray()
    history.forEach {
      contents.put(
          JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", it.user))),
      )
      contents.put(
          JSONObject()
              .put("role", "model")
              .put("parts", JSONArray().put(JSONObject().put("text", it.assistant))),
      )
    }
    val parts = JSONArray()
    if (jpeg != null) {
      parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", jpeg)))
    }
    if (audio != null) {
      parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/$audioFormat").put("data", audio)))
    }
    parts.put(JSONObject().put("text", text))
    contents.put(JSONObject().put("role", "user").put("parts", parts))
    val body =
        JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens * 4))

    val json =
        post(
            "https://generativelanguage.googleapis.com/v1beta/models/${Prefs.geminiModel}:generateContent",
            mapOf("x-goog-api-key" to key),
            body.toString(),
        )
    val cands = json.optJSONArray("candidates")
    if (cands == null || cands.length() == 0) error("Gemini не дал ответа")
    val out = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
    val sb = StringBuilder()
    for (i in 0 until out.length()) {
      val p = out.getJSONObject(i)
      if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
    }
    return sb.toString().trim().ifBlank { error("Gemini вернул пустой ответ") }
  }

  // ---------- общее ----------
  private fun post(url: String, headers: Map<String, String>, body: String): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 15_000
      conn.readTimeout = 120_000
      conn.doOutput = true
      conn.setRequestProperty("content-type", "application/json")
      headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
      conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      if (code !in 200..299) {
        val msg =
            runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                ?: text.take(200)
        error("Ошибка ИИ ($code): $msg")
      }
      return JSONObject(text)
    } finally {
      conn.disconnect()
    }
  }

  /** Уменьшаем фото до 1024 px по длинной стороне: так дешевле и быстрее. */
  private fun toBase64Jpeg(src: Bitmap): String {
    val maxSide = 1024
    val scale = maxSide.toFloat() / maxOf(src.width, src.height)
    val bmp =
        if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }
}
