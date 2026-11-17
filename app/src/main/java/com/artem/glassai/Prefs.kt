package com.artem.glassai

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения: ключи API, модель, языки. Хранятся только на телефоне. */
object Prefs {
  private lateinit var sp: SharedPreferences

  fun init(context: Context) {
    sp = context.getSharedPreferences("glassai", Context.MODE_PRIVATE)
  }

  private fun str(key: String, def: String) = sp.getString(key, def) ?: def
  private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

  /** "claude" или "gemini" */
  var provider: String
    get() = str("provider", "claude")
    set(v) = put("provider", v)

  var claudeKey: String
    get() = str("claudeKey", "")
    set(v) = put("claudeKey", v.trim())

  var claudeModel: String
    get() = str("claudeModel", "claude-haiku-5-5")
    set(v) = put("claudeModel", v.trim())

  var geminiKey: String
    get() = str("geminiKey", "")
    set(v) = put("geminiKey", v.trim())

  var geminiModel: String
    get() = str("geminiModel", "gemini-flash-latest")
    set(v) = put("geminiModel", v.trim())

  /** Мой язык (на нём задаю вопросы и слышу ответы) */
  var myLang: String
    get() = str("myLang", "ru-RU")
    set(v) = put("myLang", v)

  /** Язык собеседника для переводчика */
  var theirLang: String
    get() = str("theirLang", "th-TH")
    set(v) = put("theirLang", v)
}

/** Языки, которые можно выбрать. Код — для распознавания и озвучки, имя — для ИИ и экрана. */
object Langs {
  val all = linkedMapOf(
      "ru-RU" to "русский",
      "th-TH" to "тайский",
      "en-US" to "английский",
      "zh-CN" to "китайский",
      "ja-JP" to "японский",
      "ko-KR" to "корейский",
      "vi-VN" to "вьетнамский",
      "hi-IN" to "хинди",
      "de-DE" to "немецкий",
      "fr-FR" to "французский",
      "es-ES" to "испанский",
      "it-IT" to "итальянский",
      "tr-TR" to "турецкий",
      "ar-SA" to "арабский",
  )

  fun name(code: String) = all[code] ?: code
}
