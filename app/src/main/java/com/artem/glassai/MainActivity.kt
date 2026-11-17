package com.artem.glassai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

class MainActivity : ComponentActivity() {

  private val androidPermissions =
      arrayOf(
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.POST_NOTIFICATIONS,
      )

  private val permissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAndroidPermissions() }

  private val cameraPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        val text =
            if (status == PermissionStatus.Granted) "Камера очков разрешена ✅"
            else "Камера очков не разрешена"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
      }

  private fun has(p: String) =
      ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun onAndroidPermissions() {
    if (has(Manifest.permission.BLUETOOTH_CONNECT)) Glasses.init(this)
    if (has(Manifest.permission.RECORD_AUDIO)) AssistantService.start(this)
    else Toast.makeText(this, "Без микрофона помощник не сможет слушать", Toast.LENGTH_LONG).show()
    Brain.startBackground()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Brain.init(this)
    enableEdgeToEdge()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF15151F)) {
          Screen(
              onRegister = {
                if (!Glasses.initialized.value) permissionLauncher.launch(androidPermissions)
                else Wearables.startRegistration(this)
              },
              onCameraPermission = {
                if (!Glasses.initialized.value) permissionLauncher.launch(androidPermissions)
                else cameraPermissionLauncher.launch(Permission.CAMERA)
              },
          )
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    if (androidPermissions.all { has(it) }) onAndroidPermissions()
    else permissionLauncher.launch(androidPermissions)
  }
}

private val Yellow = Color(0xFFFFD166)
private val Green = Color(0xFF2A9D8F)
private val Red = Color(0xFFE76F51)

// ======================= Экран =======================

@Composable
private fun Screen(onRegister: () -> Unit, onCameraPermission: () -> Unit) {
  val context = LocalContext.current
  val status by Brain.status.collectAsState()
  val busy by Brain.busy.collectAsState()
  val translatorOn by Brain.translatorOn.collectAsState()
  val guideOn by Brain.guideOn.collectAsState()
  val recordingOn by Brain.recordingOn.collectAsState()
  val photo by Brain.lastPhoto.collectAsState()
  val big by Brain.bigText.collectAsState()
  val log by Brain.log.collectAsState()
  val saved by Library.items.collectAsState()
  val initialized by Glasses.initialized.collectAsState()
  val device by Glasses.device.collectAsState()
  val controlsActive by Glasses.controlsActive.collectAsState()

  var myLang by remember { mutableStateOf(Prefs.myLang) }
  var theirLang by remember { mutableStateOf(Prefs.theirLang) }
  var dialog by remember { mutableStateOf(Prefs.dialog) }
  var controls by remember { mutableStateOf(Prefs.controls) }
  var readNotifs by remember { mutableStateOf(Prefs.readNotifs) }
  var recGlasses by remember { mutableStateOf(Prefs.recGlassesMic) }
  var guideSec by remember { mutableStateOf(Prefs.guideSec) }

  Column(
      modifier =
          Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Очки ИИ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Yellow)
    Text(status, fontSize = 16.sp, color = Color.White)
    device?.let { d ->
      val battery = if (d.batteryLevel > 0) "${d.batteryLevel}%" else "заряд неизвестен"
      val worn =
          when (d.donState.name) {
            "DONNED" -> " · надеты"
            "DOFFED" -> " · сняты"
            else -> ""
          }
      val charging = if (d.chargingState.name == "CHARGING") " · заряжаются" else ""
      Text("🔋 Очки: $battery$worn$charging", fontSize = 14.sp, color = Color(0xFFBBBBBB))
    }

    // ---------- Подключение ----------
    Section("Подключение очков") {
      if (initialized) RegistrationLine()
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRegister, modifier = Modifier.weight(1f)) { Text("Подключить") }
        OutlinedButton(onClick = onCameraPermission, modifier = Modifier.weight(1f)) {
          Text("Разрешить камеру")
        }
      }
      OutlinedButton(onClick = { Brain.testCamera() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("📷  Проверить камеру очков")
      }
    }

    // ---------- Помощник ----------
    Section("ИИ-помощник") {
      Button(
          onClick = { Brain.ask() },
          enabled = (!busy || translatorOn) && !recordingOn,
          modifier = Modifier.fillMaxWidth().height(64.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black),
      ) {
        Text("🎤  Спросить", fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
      Toggle("Диалог: после ответа слушать дальше", dialog) {
        dialog = it
        Prefs.dialog = it
      }
      Hint("Спрашивай о чём угодно — не только про то, что видишь. В диалоге можно уточнять без нажатия; " +
          "закончить — «стоп», «спасибо» или просто помолчать. Голосовые команды: «запиши …» (заметка), " +
          "«сфотографируй», «сохрани это», «включи гида», «включи диктофон», «включи переводчик».")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Brain.fullPhoto() }, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text("📷 Фото", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { Brain.saveView() }, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text("⭐ Сохранить", fontSize = 13.sp)
        }
        OutlinedButton(onClick = { Brain.noteVoice() }, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text("📝 Заметка", fontSize = 13.sp)
        }
      }
      Hint("Фото — снимок в полном качестве в галерею (папка GlassAI). Сохранить — кадр с описанием от ИИ. " +
          "Заметка — надиктуй, текст попадёт в Documents/GlassAI/Заметки.txt.")
      photo?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Последний кадр",
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
      }
    }

    // ---------- Гид ----------
    Section("Режим гида") {
      Button(
          onClick = { if (guideOn) Brain.stopGuide() else Brain.startGuide() },
          enabled = !recordingOn,
          modifier = Modifier.fillMaxWidth().height(52.dp),
          colors = ButtonDefaults.buttonColors(containerColor = if (guideOn) Red else Green),
      ) {
        Text(if (guideOn) "■  Выключить гида" else "🧭  Включить гида", fontSize = 16.sp)
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Смотреть раз в:", color = Color.White, fontSize = 13.sp)
        listOf(10, 20, 30, 60).forEach { sec ->
          OutlinedButton(
              onClick = { guideSec = sec; Prefs.guideSec = sec },
              colors =
                  ButtonDefaults.outlinedButtonColors(
                      contentColor = if (guideSec == sec) Yellow else Color(0xFF9A9AB0),
                  ),
          ) {
            Text("$sec с", fontSize = 12.sp)
          }
        }
      }
      Hint("Гид сам смотрит через очки и рассказывает, когда видит интересное: вывеску, меню, цену, " +
          "достопримечательность. Каждая находка сохраняется с фото и описанием в «Сохранённое».")
    }

    // ---------- Переводчик ----------
    Section("ИИ-переводчик") {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LangPicker("Собеседник", theirLang, Modifier.weight(1f)) {
          theirLang = it
          Prefs.theirLang = it
        }
        Text("→", color = Color.White, fontSize = 20.sp)
        LangPicker("Я", myLang, Modifier.weight(1f)) {
          myLang = it
          Prefs.myLang = it
        }
      }
      Button(
          onClick = { if (translatorOn) Brain.stopAll() else Brain.startTranslator() },
          enabled = !recordingOn,
          modifier = Modifier.fillMaxWidth().height(52.dp),
          colors = ButtonDefaults.buttonColors(containerColor = if (translatorOn) Red else Green),
      ) {
        Text(if (translatorOn) "■  Выключить переводчик" else "🌐  Включить переводчик", fontSize = 16.sp)
      }
      OutlinedButton(onClick = { Brain.replyMine() }, enabled = !recordingOn, modifier = Modifier.fillMaxWidth()) {
        Text("🗣  Мой ответ — перевести вслух через телефон")
      }
      if (big.isNotBlank()) {
        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp)) {
          Text(big, fontSize = 28.sp, color = Color.Black, fontWeight = FontWeight.Medium)
        }
      }
    }

    // ---------- Диктофон ----------
    Section("Диктофон встреч") {
      Button(
          onClick = { if (recordingOn) Brain.stopRecording() else Brain.startRecording() },
          modifier = Modifier.fillMaxWidth().height(52.dp),
          colors = ButtonDefaults.buttonColors(containerColor = if (recordingOn) Red else Green),
      ) {
        Text(if (recordingOn) "■  Остановить и расшифровать" else "⏺  Начать запись", fontSize = 16.sp)
      }
      Toggle("Писать с микрофона очков (иначе — телефона)", recGlasses) {
        recGlasses = it
        Prefs.recGlassesMic = it
      }
      Hint("После остановки ИИ расшифрует запись и составит итог: суть, договорённости, задачи. " +
          "Текст — в Documents/GlassAI, звук — в Recordings/GlassAI. Нужен OpenRouter или Gemini. " +
          "Микрофон телефона на столе обычно лучше слышит всех участников.")
    }

    // ---------- Тачпад ----------
    Section("Управление с дужки очков") {
      Toggle("Слушать тачпад на дужке", controls) {
        controls = it
        Prefs.controls = it
        Brain.applyControls()
      }
      Text(
          if (controlsActive) "Связь с очками: активна ✅" else if (controls) "Связь с очками: подключаюсь…" else "Выключено",
          color = Color(0xFFBBBBBB),
          fontSize = 13.sp,
      )
      Hint("Тап — спросить (или остановить). Смахнуть вперёд — гид вкл/выкл. Смахнуть назад — заметка. " +
          "Кнопка съёмки остаётся штатной. Функция экспериментальная: каждый жест пишется в журнал, " +
          "по нему подстроим. Пока включено, тачпад может не управлять музыкой и громкостью.")
    }

    // ---------- Уведомления ----------
    Section("Чтение уведомлений вслух") {
      Toggle("Читать сообщения, звонки и напоминания в очки", readNotifs) {
        readNotifs = it
        Prefs.readNotifs = it
      }
      OutlinedButton(
          onClick = {
            runCatching {
              context.startActivity(
                  Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
              )
            }
          },
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Дать доступ к уведомлениям (один раз)")
      }
      Hint("В открывшемся списке включи «Очки ИИ». Читаются только сообщения, почта, звонки, события.")
    }

    OutlinedButton(onClick = { Brain.stopAll() }, modifier = Modifier.fillMaxWidth()) { Text("■ Стоп всё") }

    // ---------- Сохранённое ----------
    if (saved.isNotEmpty()) {
      Section("Сохранённое (${saved.size})") {
        saved.take(25).forEach { item ->
          Text(
              "${item.time} · ${item.kind}: ${item.text.take(260)}",
              color = Color(0xFFDDDDDD),
              fontSize = 14.sp,
              modifier = Modifier.fillMaxWidth().clickable { openSaved(context, item) },
          )
          HorizontalDivider()
        }
        Hint("Нажми на запись, чтобы открыть фото или текст.")
      }
    }

    // ---------- Журнал ----------
    if (log.isNotEmpty()) {
      Section("Журнал") {
        log.forEach { line ->
          Text("${line.who}: ${line.text}", color = Color(0xFFDDDDDD), fontSize = 14.sp)
        }
        OutlinedButton(onClick = { Brain.clearLog() }) { Text("Очистить") }
      }
    }

    AiSettings()
    Spacer(Modifier.height(40.dp))
  }
}

private fun openSaved(context: Context, item: Library.Item) {
  val uri = item.uri ?: return
  val mime = if (item.kind == "Находка" || item.kind == "Фото") "image/*" else "text/plain"
  runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun RegistrationLine() {
  val state by Wearables.registrationState.collectAsState(initial = null)
  var camOk by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(state) { camOk = runCatching { Glasses.isCameraAllowed() }.getOrNull() }
  val reg = state?.toString() ?: "…"
  val regRu =
      when {
        reg.contains("UNREGISTER") -> "отключается"
        reg.contains("REGISTERED") -> "подключено ✅"
        reg.contains("REGISTERING") -> "подключается…"
        else -> "не подключено"
      }
  val cam =
      when (camOk) {
        true -> "разрешена ✅"
        false -> "не разрешена"
        null -> "…"
      }
  Text("Приложение в Meta AI: $regRu · Камера: $cam", color = Color(0xFFBBBBBB), fontSize = 14.sp)
}

@Composable
private fun AiSettings() {
  var provider by remember { mutableStateOf(Prefs.provider) }
  var claudeKey by remember { mutableStateOf(Prefs.claudeKey) }
  var claudeModel by remember { mutableStateOf(Prefs.claudeModel) }
  var geminiKey by remember { mutableStateOf(Prefs.geminiKey) }
  var geminiModel by remember { mutableStateOf(Prefs.geminiModel) }
  var orKey by remember { mutableStateOf(Prefs.openrouterKey) }
  var orModel by remember { mutableStateOf(Prefs.openrouterModel) }

  Section("Настройки ИИ") {
    Row(verticalAlignment = Alignment.CenterVertically) {
      listOf("openrouter" to "OpenRouter", "claude" to "Claude", "gemini" to "Gemini").forEach { (id, title) ->
        RadioButton(selected = provider == id, onClick = { provider = id; Prefs.provider = id })
        Text(title, color = Color.White, fontSize = 14.sp)
      }
    }
    when (provider) {
      "openrouter" -> {
        KeyField("Ключ OpenRouter (sk-or-…)", orKey) { orKey = it; Prefs.openrouterKey = it }
        ModelField(orModel) { orModel = it; Prefs.openrouterModel = it }
        Hint("google/gemini-3.1-flash-lite — быстро и очень дёшево. Умнее: google/gemini-3.5-flash " +
            "(список на openrouter.ai/models). Модель должна понимать картинки, а для диктофона — и звук.")
      }
      "claude" -> {
        KeyField("Ключ Claude API (sk-ant-…)", claudeKey) { claudeKey = it; Prefs.claudeKey = it }
        ModelField(claudeModel) { claudeModel = it; Prefs.claudeModel = it }
        Hint("claude-haiku-5-5 — быстро и дёшево; claude-sonnet-5-5 — умнее. Диктофон с Claude не работает.")
      }
      else -> {
        KeyField("Ключ Gemini API (AIza…)", geminiKey) { geminiKey = it; Prefs.geminiKey = it }
        ModelField(geminiModel) { geminiModel = it; Prefs.geminiModel = it }
        Hint("Ключ бесплатно берётся в Google AI Studio (aistudio.google.com → Get API key).")
      }
    }
  }
}

@Composable
private fun ModelField(value: String, onChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = onChange,
      label = { Text("Модель") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
  OutlinedTextField(
      value = value,
      onValueChange = onChange,
      label = { Text(label) },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun LangPicker(title: String, code: String, modifier: Modifier, onPick: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box(modifier) {
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
      Text("$title: ${Langs.name(code)}", fontSize = 13.sp)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      Langs.all.forEach { (c, n) ->
        DropdownMenuItem(text = { Text(n) }, onClick = { onPick(c); open = false })
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
      HorizontalDivider()
      content()
    }
  }
}

@Composable
private fun Hint(text: String) {
  Text(text, fontSize = 13.sp, color = Color(0xFF9A9AB0))
}
