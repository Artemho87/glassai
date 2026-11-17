package com.artem.glassai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Сохранение на телефон, в обычные папки (видны в «Галерее» и «Файлах»):
 * - фото: Pictures/GlassAI
 * - тексты (заметки, находки, встречи): Documents/GlassAI
 * - записи диктофона: Recordings/GlassAI
 */
object Storage {
  private lateinit var ctx: Context

  fun init(context: Context) {
    ctx = context.applicationContext
  }

  fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

  fun humanTime(): String = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())

  fun saveBitmap(bmp: Bitmap, name: String): Uri? {
    val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
          put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlassAI")
        }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return uri
  }

  /** Сохраняет готовый файл фото как есть (без пережатия). */
  fun saveImageBytes(bytes: ByteArray, name: String): Uri? {
    val isJpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    val ext = if (isJpeg) "jpg" else "heic"
    val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
          put(MediaStore.Images.Media.MIME_TYPE, if (isJpeg) "image/jpeg" else "image/heic")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlassAI")
        }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    return uri
  }

  fun saveAudio(file: File, name: String): Uri? {
    val values =
        ContentValues().apply {
          put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.aac")
          put(MediaStore.Audio.Media.MIME_TYPE, "audio/aac")
          put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/GlassAI")
        }
    val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    return uri
  }

  private val filesUri: Uri
    get() = MediaStore.Files.getContentUri("external")

  private fun findText(fileName: String): Uri? {
    val proj = arrayOf(MediaStore.MediaColumns._ID)
    val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val args = arrayOf(fileName, "%Documents/GlassAI%")
    return runCatching {
          ctx.contentResolver.query(filesUri, proj, sel, args, null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(filesUri, c.getLong(0)) else null
          }
        }
        .getOrNull()
  }

  /** Новый текстовый файл в Documents/GlassAI. */
  fun saveText(name: String, text: String): Uri? {
    val values =
        ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.txt")
          put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/GlassAI")
        }
    val uri = ctx.contentResolver.insert(filesUri, values) ?: return null
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return uri
  }

  /** Дописывает строку в общий файл (например «Заметки.txt»). */
  fun appendText(name: String, line: String): Uri? {
    val existing = findText("$name.txt")
    if (existing != null) {
      val ok =
          runCatching {
                ctx.contentResolver.openOutputStream(existing, "wa")?.use {
                  it.write(("\n" + line).toByteArray(Charsets.UTF_8))
                }
              }
              .isSuccess
      if (ok) return existing
    }
    return saveText(name, line)
  }
}

/** Список сохранённого: находки гида, заметки, фото, встречи. Показывается в приложении. */
object Library {
  data class Item(val time: String, val kind: String, val text: String, val uri: String?)

  private lateinit var file: File
  val items = kotlinx.coroutines.flow.MutableStateFlow<List<Item>>(emptyList())

  fun init(context: Context) {
    file = File(context.filesDir, "library.json")
    items.value =
        runCatching {
              val arr = JSONArray(file.readText())
              (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.optString("time"), o.optString("kind"), o.optString("text"), o.optString("uri").ifBlank { null })
              }
            }
            .getOrDefault(emptyList())
  }

  fun add(kind: String, text: String, uri: Uri?) {
    val list = (listOf(Item(Storage.humanTime(), kind, text, uri?.toString())) + items.value).take(300)
    items.value = list
    runCatching {
      val arr = JSONArray()
      list.forEach {
        arr.put(JSONObject().put("time", it.time).put("kind", it.kind).put("text", it.text).put("uri", it.uri ?: ""))
      }
      file.writeText(arr.toString())
    }
  }
}
