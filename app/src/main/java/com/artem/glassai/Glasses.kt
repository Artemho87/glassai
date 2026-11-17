package com.artem.glassai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Камера очков через Meta Wearables Device Access Toolkit.
 *
 * Важно: камеру занимаем только на 2–4 секунды ради одного кадра, потом сразу отпускаем.
 * Поэтому штатная съёмка («Hey Meta, сфотографируй», кнопка на дужке) работает как раньше.
 */
object Glasses {

  val initialized = MutableStateFlow(false)

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

  /** Делает один снимок с камеры очков. Бросает GlassesException с понятным текстом. */
  suspend fun capture(): Bitmap {
    if (!initialized.value) throw GlassesException("Очки ещё не подключены к приложению")
    if (!isCameraAllowed()) {
      throw GlassesException("Нет доступа к камере очков: в приложении нажми «Разрешить камеру»")
    }

    var session: DeviceSession? = null
    var camera: Camera? = null
    try {
      val createdResult = Wearables.createSession(AutoDeviceSelector())
      val created: DeviceSession =
          createdResult.getOrNull()
              ?: throw GlassesException(
                  "Очки не найдены: ${createdResult.errorOrNull()?.description ?: "нет подключения"}",
              )
      session = created
      created.start()

      val sessionState =
          withTimeoutOrNull(12_000) {
            created.state.first {
              it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED
            }
          }
      if (sessionState != DeviceSessionState.STARTED) {
        throw GlassesException("Очки не ответили. Надень их и проверь Bluetooth")
      }

      // compressVideo = true: сжатый поток не ставится на паузу, когда приложение свёрнуто
      // (например, вопрос задан кнопкой из уведомления на заблокированном экране).
      val camResult =
          created.addCamera(
              StreamConfiguration(
                  videoQuality = VideoQuality.MEDIUM,
                  frameRate = 15,
                  compressVideo = true,
              ),
          )
      val cam: Camera =
          camResult.getOrNull()
              ?: throw GlassesException("Камера занята: ${camResult.errorOrNull()?.description}")
      camera = cam
      val stream = cam.stream

      var startError: String? = null
      stream.start().onFailure { e, _ -> startError = e.description }
      startError?.let { throw GlassesException("Камера не запустилась: $it") }

      val streamState =
          withTimeoutOrNull(12_000) {
            stream.state.first { it == StreamState.STREAMING || it == StreamState.CLOSED }
          }
      if (streamState != StreamState.STREAMING) {
        throw GlassesException("Видео с очков не пошло, попробуй ещё раз")
      }

      delay(600) // даём камере выставить экспозицию

      val photoResult = stream.capturePhoto()
      val photo: PhotoData =
          photoResult.getOrNull()
              ?: throw GlassesException("Не удалось снять кадр: ${photoResult.errorOrNull()?.description}")
      return decode(photo) ?: throw GlassesException("Кадр пришёл повреждённым")
    } finally {
      runCatching { camera?.close() }
      runCatching { session?.stop() }
    }
  }

  private fun decode(photo: PhotoData): Bitmap? =
      when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
          val buf = photo.data.duplicate().apply { rewind() }
          val bytes = ByteArray(buf.remaining())
          buf.get(bytes)
          val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          if (bmp == null) null else rotateByExif(bmp, bytes)
        }
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
