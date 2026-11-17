package com.artem.glassai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Очки через Meta Wearables Device Access Toolkit: камера, тачпад на дужке, заряд.
 *
 * Камеру занимаем только на несколько секунд ради одного кадра и сразу отпускаем,
 * поэтому штатная съёмка («Hey Meta», кнопка на дужке) продолжает работать.
 */
object Glasses {

  val initialized = MutableStateFlow(false)

  /** Состояние очков: заряд, надеты ли, на зарядке ли. null — очки не найдены. */
  val device = MutableStateFlow<Device?>(null)

  /** true, пока приложение слушает тачпад на дужке */
  val controlsActive = MutableStateFlow(false)

  /** Очки требуют обновить свой компонент для сторонних приложений (делается в Meta AI). */
  val needsUpdate = MutableStateFlow(false)

  const val UPDATE_HELP =
      "Очкам нужно обновление для сторонних приложений. Открой Meta AI → настройки очков → " +
          "App Connections (Подключённые приложения) → обнови или установи компонент, потом нажми «Проверить камеру»"

  private val mutex = Mutex()
  @Volatile private var held: DeviceSession? = null

  fun init(context: Context) {
    if (initialized.value) return
    val btGranted =
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!btGranted) return
    runCatching { Wearables.initialize(context.applicationContext) }
        .onSuccess { initialized.value = true }
  }

  class GlassesException(message: String) : Exception(message)

  suspend fun isCameraAllowed(): Boolean {
    var granted = false
    Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { status ->
      granted = status == PermissionStatus.Granted
    }
    return granted
  }

  // ---------------- сеанс связи с очками ----------------

  private suspend fun openSession(onStep: (String) -> Unit): DeviceSession {
    onStep("подключаюсь к очкам")
    val createdResult = Wearables.createSession(AutoDeviceSelector())
    val s =
        createdResult.getOrNull()
            ?: throw GlassesException(
                "Очки не найдены: ${createdResult.errorOrNull()?.description ?: "нет подключения"}",
            )
    var lastError: String? = null
    var updateRequired = false
    val errJob =
        CoroutineScope(Dispatchers.Main).launch {
          s.errors.collect {
            lastError = it.description
            if (it.toString().contains("UPDATE_REQUIRED") || it.description.contains("need an update")) {
              updateRequired = true
            }
          }
        }
    try {
      s.start()
      val st =
          withTimeoutOrNull(20_000) {
            s.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED }
          }
      if (st != DeviceSessionState.STARTED) {
        runCatching { s.stop() }
        if (updateRequired) {
          needsUpdate.value = true
          throw GlassesException(UPDATE_HELP)
        }
        throw GlassesException(
            "Очки не ответили (сеанс: ${st ?: s.state.value}${lastError?.let { ", $it" } ?: ""}). " +
                "Надень очки, открой дужки, проверь Bluetooth",
        )
      }
      needsUpdate.value = false
    } finally {
      errJob.cancel()
    }
    return s
  }

  /** Выполняет работу в сеансе: в постоянном (если включено управление с дужки) или во временном. */
  private suspend fun <T> withSession(
      onStep: (String) -> Unit,
      block: suspend (DeviceSession) -> T,
  ): T =
      mutex.withLock {
        if (!initialized.value) throw GlassesException("Очки ещё не подключены к приложению")
        val h = held
        if (h != null) {
          if (h.state.value != DeviceSessionState.STARTED) {
            throw GlassesException("Очки на паузе (сеанс: ${h.state.value}). Надень их и повтори")
          }
          block(h)
        } else {
          val s = openSession(onStep)
          try {
            block(s)
          } finally {
            runCatching { s.stop() }
          }
        }
      }

  private suspend fun requireCamera(onStep: (String) -> Unit, force: Boolean = false) {
    onStep("проверяю подключение")
    // пока очки не обновлены, не тратим по 20 секунд на каждую попытку
    if (needsUpdate.value && !force) throw GlassesException(UPDATE_HELP)
    if (!initialized.value) throw GlassesException("Очки ещё не подключены к приложению")
    if (!isCameraAllowed()) {
      throw GlassesException("Нет доступа к камере очков: в приложении нажми «Разрешить камеру»")
    }
  }

  // ---------------- быстрый кадр для ИИ ----------------

  /**
   * Быстрый кадр (из видеопотока) для ИИ.
   *
   * @param onStep этапы для экрана
   * @param onStreaming вызывается, когда видео пошло или всё сломалось — после этого можно
   *   включать микрофон очков, не мешая запуску камеры
   */
  suspend fun capture(
      onStep: (String) -> Unit = {},
      onStreaming: () -> Unit = {},
      force: Boolean = false,
  ): Bitmap {
    try {
      requireCamera(onStep, force)
      return withSession(onStep) { session ->
        val camResult =
            session.addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = 15,
                    // сжатый поток не встаёт на паузу, когда приложение свёрнуто
                    compressVideo = true,
                ),
            )
        val cam: Camera =
            camResult.getOrNull()
                ?: throw GlassesException("Камера занята: ${camResult.errorOrNull()?.description}")
        try {
          val stream = cam.stream
          onStep("запускаю камеру")
          var startError: String? = null
          stream.start().onFailure { e, _ -> startError = e.description }
          startError?.let { throw GlassesException("Камера не запустилась: $it") }

          val streamState =
              withTimeoutOrNull(20_000) {
                stream.state.first { it == StreamState.STREAMING || it == StreamState.CLOSED }
              }
          if (streamState != StreamState.STREAMING) {
            throw GlassesException("Видео с очков не пошло (поток: ${streamState ?: stream.state.value})")
          }
          onStreaming()
          onStep("снимаю кадр")
          delay(600) // даём камере выставить экспозицию

          val photoResult = stream.capturePhoto()
          val photo: PhotoData =
              photoResult.getOrNull()
                  ?: throw GlassesException(
                      "Не удалось снять кадр: ${photoResult.errorOrNull()?.description}",
                  )
          decode(photo) ?: throw GlassesException("Кадр пришёл повреждённым")
        } finally {
          runCatching { cam.close() }
          runCatching { session.removeCamera() }
        }
      }
    } finally {
      onStreaming()
    }
  }

  // ---------------- фото в полном качестве ----------------

  /** Снимок в полном разрешении камеры очков. Возвращает байты готового файла (JPEG/HEIC). */
  suspend fun captureFull(onStep: (String) -> Unit = {}): ByteArray {
    requireCamera(onStep)
    return withSession(onStep) { session ->
      val camResult =
          session.addCamera(StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 15))
      val cam: Camera =
          camResult.getOrNull()
              ?: throw GlassesException("Камера занята: ${camResult.errorOrNull()?.description}")
      try {
        coroutineScope {
          onStep("готовлю фотокамеру")
          val photo = cam.photo
          val result = CompletableDeferred<ByteArray>()
          val dataJob = launch { photo.photoStream.collect { result.complete(it.imageData) } }
          val errJob =
              launch {
                photo.errors.collect {
                  result.completeExceptionally(GlassesException("Фото не снялось: ${it.description}"))
                }
              }
          val progressJob =
              launch { photo.transferProgressStream.collect { onStep("передаю фото с очков") } }
          try {
            val st = withTimeoutOrNull(15_000) { photo.state.first { it == PhotoState.STARTED } }
            if (st == null) throw GlassesException("Фотокамера не запустилась (${photo.state.value})")
            onStep("снимаю в полном качестве")
            photo.capturePhoto(PhotoResolution.FULL, PhotoQuality.HIGH)
            withTimeoutOrNull(45_000) { result.await() }
                ?: throw GlassesException("Фото не пришло с очков за 45 секунд")
          } finally {
            dataJob.cancel()
            errJob.cancel()
            progressJob.cancel()
            runCatching { photo.stop() }
          }
        }
      } finally {
        runCatching { cam.close() }
        runCatching { session.removeCamera() }
      }
    }
  }

  // ---------------- тачпад на дужке ----------------

  /**
   * Держит постоянный сеанс с очками и слушает тачпад на дужке.
   * Кнопку съёмки не трогаем — она остаётся штатной.
   * События: "tap", "nav_UP/DOWN/LEFT/RIGHT", "capture_…", "button", "back".
   */
  fun startControls(
      scope: CoroutineScope,
      onEvent: (String) -> Unit,
      onLog: (String) -> Unit,
      onGiveUp: () -> Unit,
  ): Job =
      scope.launch {
        var failures = 0
        while (isActive) {
          var mine: DeviceSession? = null
          try {
            val s =
                mutex.withLock {
                  val opened = openSession {}
                  held = opened
                  mine = opened
                  opened
                }
            val res =
                s.addInputs(
                    InputsConfiguration(sources = setOf(InputSource.CAPTOUCH), consumeBack = false),
                )
            val inputs =
                res.getOrNull()
                    ?: throw GlassesException("тачпад не подключился: ${res.errorOrNull()?.description}")
            controlsActive.value = true
            failures = 0
            onLog("Управление с дужки включено")
            coroutineScope {
              val ev =
                  launch {
                    inputs.events.collect { e ->
                      val name =
                          when (e) {
                            is InputEvent.Select -> "tap"
                            is InputEvent.Nav -> "nav_${e.direction.name}"
                            is InputEvent.Capture -> "capture_${e.pressType.name}"
                            is InputEvent.Button -> "button"
                            is InputEvent.Back -> "back"
                            else -> "other"
                          }
                      onEvent(name)
                    }
                  }
              val er =
                  launch {
                    inputs.errors.collect { err -> if (err != null) onLog("Тачпад: ${err.description}") }
                  }
              s.state.first { it == DeviceSessionState.STOPPED }
              ev.cancel()
              er.cancel()
            }
          } catch (c: CancellationException) {
            throw c
          } catch (e: Exception) {
            failures++
            onLog("Управление с дужки: ${e.message}")
            if (needsUpdate.value) failures = 99
          } finally {
            controlsActive.value = false
            if (held === mine) held = null
            runCatching { mine?.stop() }
          }
          if (failures >= 4) {
            onLog("Управление с дужки выключено. Включи снова, когда связь с очками заработает")
            onGiveUp()
            break
          }
          delay(if (failures == 0) 5_000L else 15_000L * failures)
        }
      }

  // ---------------- заряд и состояние ----------------

  fun startDeviceMonitor(scope: CoroutineScope): Job =
      scope.launch {
        Wearables.devices.collectLatest { ids ->
          val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
          if (flows.isEmpty()) {
            device.value = null
          } else {
            combine(flows) { it.toList() }
                .collect { list ->
                  device.value =
                      list.firstOrNull { it.linkState == LinkState.CONNECTED } ?: list.firstOrNull()
                }
          }
        }
      }

  // ---------------- разбор снимка ----------------

  private fun decode(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val buf = photo.data.duplicate().apply { rewind() }
          val bytes = ByteArray(buf.remaining())
          buf.get(bytes)
          decodeBytes(bytes)
        }
      }

  /** Превращает байты фото в картинку с правильным поворотом. */
  fun decodeBytes(bytes: ByteArray, maxSide: Int = 0): Bitmap? {
    val opts = BitmapFactory.Options()
    if (maxSide > 0) {
      val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
      var sample = 1
      while (maxOf(probe.outWidth, probe.outHeight) / (sample * 2) >= maxSide) sample *= 2
      opts.inSampleSize = sample
    }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    return rotateByExif(bmp, bytes)
  }

  private fun rotateByExif(bmp: Bitmap, bytes: ByteArray): Bitmap {
    val orientation =
        runCatching {
              ExifInterface(ByteArrayInputStream(bytes))
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees =
        when (orientation) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> 0f
        }
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }
        .getOrDefault(bmp)
  }
}
