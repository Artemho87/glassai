package com.artem.glassai

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения: ключи API, модель, языки, переключатели. Хранятся только на телефоне. */
object Prefs {
  private var sp: SharedPreferences? = null

  fun init(context: Context) {
    if (sp == null) sp = context.applicationContext.getSharedPreferences("glassai", Context.MODE_PRIVATE)
  }

  private fun str(key: String, def: String) = sp?.getString(key, def) ?: def
  private fun put(key: String, value: String) {
    sp?.edit()?.putString(key, value)?.apply()
  }
  private fun bool(key: String, def: Boolean) = sp?.getBoolean(key, def) ?: def
  private fun put(key: String, value: Boolean) {
    sp?.edit()?.putBoolean(key, value)?.apply()
  }

  /** "openrouter", "claude" или "gemini" */
  var provider: String
    get() = str("provider", "openrouter")
    set(v) = put("provider", v)

  var claudeKey: String
    get() = str("claudeKey", "")
    set(v) = put("claudeKey", v.trim())

  var claudeModel: String
    get() = str("claudeModel", "claude-haiku-5-5")
    set(v) = put("claudeModel", v.trim())

  var openrouterKey: String
    get() = str("openrouterKey", "")
    set(v) = put("openrouterKey", v.trim())

  var openrouterModel: String
    get() = str("openrouterModel", "google/gemini-3.1-flash-lite")
    set(v) = put("openrouterModel", v.trim())

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

  /** После ответа слушать следующий вопрос без нажатия */
  var dialog: Boolean
    get() = bool("dialog", true)
    set(v) = put("dialog", v)

  /** Управление тачпадом на дужке (держит постоянную связь с очками) */
  var controls: Boolean
    get() = bool("controls", false)
    set(v) = put("controls", v)

  /** Читать уведомления вслух */
  var readNotifs: Boolean
    get() = bool("readNotifs", false)
    set(v) = put("readNotifs", v)

  /** Диктофон пишет с микрофона очков (иначе — телефона) */
  var recGlassesMic: Boolean
    get() = bool("recGlassesMic", false)
    set(v) = put("recGlassesMic", v)

  /** Как часто гид смотрит вокруг, секунд */
  var guideSec: Int
    get() = str("guideSec", "20").toIntOrNull() ?: 20
    set(v) = put("guideSec", v.toString())
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
