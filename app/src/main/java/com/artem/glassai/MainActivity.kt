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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
        val text = if (status == PermissionStatus.Granted) "Камера очков разрешена ✅" else "Камера очков не разрешена"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
      }

  private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

  private fun onAndroidPermissions() {
    if (has(Manifest.permission.BLUETOOTH_CONNECT)) Glasses.init(this)
    if (has(Manifest.permission.RECORD_AUDIO)) AssistantService.start(this)
    else Toast.makeText(this, "Без микрофона помощник не сможет слушать", Toast.LENGTH_LONG).show()
    Brain.startBackground()
  }

  /** Фото, присланное через «Поделиться» */
  private fun handleShare(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND) return
    if (intent.type?.startsWith("image/") != true) return
    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return
    Brain.analyzeUri(uri)
    setIntent(Intent(this, MainActivity::class.java))
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
    handleShare(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShare(intent)
  }

  override fun onStart() {
    super.onStart()
    if (androidPermissions.all { has(it) }) onAndroidPermissions() else permissionLauncher.launch(androidPermissions)
  }
}

private val Yellow = Color(0xFFFFD166)
private val Green = Color(0xFF2A9D8F)
private val Red = Color(0xFFE76F51)
private val Gray = Color(0xFF9A9AB0)

// ======================= Экран =======================

@Composable
private fun Screen(onRegister: () -> Unit, onCameraPermission: () -> Unit) {
  val context = LocalContext.current
  val status by Brain.status.collectAsState()
  val busy by Brain.busy.collectAsState()
  val translatorOn by Brain.translatorOn.collectAsState()
  val recordingOn by Brain.recordingOn.collectAsState()
  val log by Brain.log.collectAsState()
  val saved by Library.items.collectAsState()
  val device by Glasses.device.collectAsState()
  val needsUpdate by Glasses.needsUpdate.collectAsState()

  var tab by remember { mutableStateOf(0) }
  var cameraFeatures by remember { mutableStateOf(Prefs.cameraFeatures) }

  Column(
      modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
    if (cameraFeatures && needsUpdate) {
      Box(Modifier.fillMaxWidth().background(Red, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("⚠ " + Glasses.UPDATE_HELP, color = Color.White, fontSize = 14.sp)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      listOf("Главная", "Переводчик", "Сохранённое", "Журнал", "Настройки").forEachIndexed { i, title ->
        OutlinedButton(
            onClick = { tab = i },
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(1.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (tab == i) Yellow else Gray),
        ) {
          Text(title, fontSize = 10.sp, maxLines = 1)
        }
      }
    }

    when (tab) {
      0 -> MainTab(busy, translatorOn, recordingOn, cameraFeatures)
      1 -> TranslatorTab(translatorOn, recordingOn)
      2 -> SavedTab(context, saved)
      3 -> LogTab(context, log)
      else ->
          SettingsTab(context, onRegister, onCameraPermission, cameraFeatures) {
            cameraFeatures = it
            Prefs.cameraFeatures = it
            Brain.applyControls()
          }
    }

    OutlinedButton(onClick = { Brain.stopAll() }, modifier = Modifier.fillMaxWidth()) { Text("■ Стоп всё") }
    Spacer(Modifier.height(40.dp))
  }
}

// ---------- Главная ----------

@Composable
private fun MainTab(busy: Boolean, translatorOn: Boolean, recordingOn: Boolean, cameraFeatures: Boolean) {
  val context = LocalContext.current
  val photo by Brain.lastPhoto.collectAsState()
  val answer by Brain.lastAnswer.collectAsState()
  val guideOn by Brain.guideOn.collectAsState()
  var dialog by remember { mutableStateOf(Prefs.dialog) }
  var recGlasses by remember { mutableStateOf(Prefs.recGlassesMic) }

  val photoPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Brain.analyzeLatest()
        else Toast.makeText(context, "Нужен доступ ко всем фото, чтобы найти снимок с очков", Toast.LENGTH_LONG).show()
      }
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) Brain.analyzeUri(uri)
      }

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
    Hint("Спрашивай о чём угодно. В диалоге можно уточнять без нажатия; закончить — «стоп», «спасибо» или " +
        "помолчать. Команды: «запиши …» (заметка), «что на фото», «сохрани это», «включи диктофон», " +
        "«включи переводчик», «новый разговор».")
  }

  Section("Разбор фото с очков") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
          onClick = {
            if (Storage.hasPhotoAccess()) Brain.analyzeLatest()
            else photoPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
          },
          enabled = !busy && !recordingOn,
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = Green),
      ) {
        Text("🖼 Последний снимок", fontSize = 13.sp)
      }
      OutlinedButton(
          onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
          enabled = !recordingOn,
          modifier = Modifier.weight(1f),
      ) {
        Text("📂 Выбрать фото", fontSize = 13.sp)
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { Brain.saveAnalysis() }, enabled = !busy, modifier = Modifier.weight(1f)) {
        Text("⭐ Сохранить разбор", fontSize = 13.sp)
      }
      OutlinedButton(onClick = { Brain.noteVoice() }, enabled = !busy && !recordingOn, modifier = Modifier.weight(1f)) {
        Text("📝 Заметка", fontSize = 13.sp)
      }
    }
    Hint("Сфотографируй очками как обычно («Hey Meta, сфотографируй» или кнопкой на дужке). Когда снимок " +
        "попадёт в галерею телефона, нажми «Последний снимок» — или в «Спросить» скажи «что на фото». " +
        "Можно и «Поделиться» любым фото → «Очки ИИ». После описания задавай уточняющие вопросы.")
    photo?.let {
      Image(bitmap = it.asImageBitmap(), contentDescription = "Последнее фото", modifier = Modifier.fillMaxWidth().height(200.dp))
    }
    if (answer.isNotBlank()) Text(answer, color = Color(0xFFDDDDDD), fontSize = 14.sp)
  }

  if (cameraFeatures) {
    Section("Камера очков напрямую (экспериментально)") {
      OutlinedButton(onClick = { Brain.testCamera() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("📷 Проверить камеру очков")
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Brain.fullPhoto() }, enabled = !busy, modifier = Modifier.weight(1f)) {
          Text("📷 Фото", fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = { if (guideOn) Brain.stopGuide() else Brain.startGuide() },
            enabled = !recordingOn,
            modifier = Modifier.weight(1f),
        ) {
          Text(if (guideOn) "■ Гид" else "🧭 Гид", fontSize = 13.sp)
        }
      }
    }
  }

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
        "Текст — в Documents/GlassAI, звук — в Recordings/GlassAI. Нужен OpenRouter или Gemini.")
  }

}

// ---------- Переводчик ----------

@Composable
private fun TranslatorTab(translatorOn: Boolean, recordingOn: Boolean) {
  val big by Brain.bigText.collectAsState()
  val translit by Brain.bigTranslit.collectAsState()
  var myLang by remember { mutableStateOf(Prefs.myLang) }
  var theirLang by remember { mutableStateOf(Prefs.theirLang) }
  var theirLang2 by remember { mutableStateOf(Prefs.theirLang2) }
  var twoWay by remember { mutableStateOf(Prefs.twoWay) }
  var engine by remember { mutableStateOf(Prefs.engine) }
  var pause by remember { mutableStateOf(Prefs.pauseMs) }
  var typed by remember { mutableStateOf("") }

  Section("Переводчик") {
    Text("Языки собеседника", color = Color.White, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      LangPicker("1", theirLang, false, Modifier.weight(1f)) {
        theirLang = it
        Prefs.theirLang = it
      }
      LangPicker("2", theirLang2, true, Modifier.weight(1f)) {
        theirLang2 = it
        Prefs.theirLang2 = it
      }
    }
    LangPicker("Мой язык", myLang, false, Modifier.fillMaxWidth()) {
      myLang = it
      Prefs.myLang = it
    }
    Toggle("В обе стороны: мои фразы переводить вслух через телефон", twoWay) {
      twoWay = it
      Prefs.twoWay = it
    }
    Text("Как слушать:", color = Color.White, fontSize = 14.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = engine == "ai", onClick = { engine = "ai"; Prefs.engine = "ai" })
      Text("ИИ (точнее, сам понимает язык)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = engine == "google", onClick = { engine = "google"; Prefs.engine = "google" })
      Text("Google (быстрее, дешевле)", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Конец фразы после паузы:", color = Color.White, fontSize = 13.sp)
      listOf(700 to "0,7 с", 1000 to "1 с", 1600 to "1,6 с").forEach { (ms, title) ->
        OutlinedButton(
            onClick = { pause = ms; Prefs.pauseMs = ms },
            contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (pause == ms) Yellow else Gray),
        ) {
          Text(title, fontSize = 12.sp)
        }
      }
    }
    Button(
        onClick = { if (translatorOn) Brain.stopAll() else Brain.startTranslator() },
        enabled = !recordingOn,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (translatorOn) Red else Green),
    ) {
      Text(if (translatorOn) "■  Выключить переводчик" else "🌐  Включить переводчик", fontSize = 17.sp)
    }
    Hint("Собеседника слышишь в очках на своём языке. Если включено «в обе стороны», просто говори по-своему — " +
        "перевод прозвучит из телефона и появится крупно ниже. Разговор сохраняется в «Сохранённое». " +
        "Режим «ИИ» не работает с провайдером Claude.")
  }

  Section("Мой ответ вручную") {
    OutlinedButton(onClick = { Brain.replyMine() }, enabled = !recordingOn, modifier = Modifier.fillMaxWidth()) {
      Text("🗣  Сказать голосом")
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text("Или напиши (в шумном месте)") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = {
          Brain.replyTyped(typed)
          typed = ""
        },
        enabled = typed.isNotBlank() && !recordingOn,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("➜  Перевести и сказать")
    }
  }

  if (big.isNotBlank()) {
    Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp)) {
      Column {
        Text(big, fontSize = 30.sp, color = Color.Black, fontWeight = FontWeight.Medium)
        if (translit.isNotBlank()) Text(translit, fontSize = 16.sp, color = Color(0xFF555555))
      }
    }
  }
}

// ---------- Сохранённое ----------

@Composable
private fun SavedTab(context: Context, saved: List<Library.Item>) {
  Section("Сохранённое (${saved.size})") {
    if (saved.isEmpty()) Hint("Пока пусто. Здесь появятся разборы фото, заметки, разговоры и итоги встреч.")
    saved.take(100).forEach { item ->
      Text(
          "${item.time} · ${item.kind}: ${item.text.take(260)}",
          color = Color(0xFFDDDDDD),
          fontSize = 14.sp,
          modifier = Modifier.fillMaxWidth().clickable { openSaved(context, item) },
      )
      HorizontalDivider()
    }
    if (saved.isNotEmpty()) Hint("Нажми на запись, чтобы открыть фото или текст.")
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

// ---------- Журнал ----------

@Composable
private fun LogTab(context: Context, log: List<Brain.Line>) {
  Section("Журнал (${log.size})") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
          onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Журнал Очки ИИ", Brain.logAsText()))
            Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.weight(1f),
      ) {
        Text("📋 Копировать всё")
      }
      OutlinedButton(onClick = { Brain.clearLog() }, modifier = Modifier.weight(1f)) { Text("Очистить") }
    }
    if (log.isEmpty()) Hint("Пока пусто. Сюда пишутся вопросы, ответы и ошибки.")
    log.forEach { line -> Text("${line.who}: ${line.text}", color = Color(0xFFDDDDDD), fontSize = 13.sp) }
  }
}

// ---------- Настройки ----------

@Composable
private fun SettingsTab(
    context: Context,
    onRegister: () -> Unit,
    onCameraPermission: () -> Unit,
    cameraFeatures: Boolean,
    onCameraFeatures: (Boolean) -> Unit,
) {
  val initialized by Glasses.initialized.collectAsState()
  val controlsActive by Glasses.controlsActive.collectAsState()
  var controls by remember { mutableStateOf(Prefs.controls) }
  var rate by remember { mutableStateOf(Prefs.speechRate) }

  Section("Голос") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Скорость:", color = Color.White, fontSize = 13.sp)
      listOf(0.85f to "медл.", 1.0f to "обычн.", 1.2f to "быстр.", 1.4f to "оч. быстр.").forEach { (r, title) ->
        OutlinedButton(
            onClick = { rate = r; Prefs.speechRate = r },
            contentPadding = PaddingValues(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (rate == r) Yellow else Gray),
        ) {
          Text(title, fontSize = 11.sp)
        }
      }
    }
  }

  Section("Работа в фоне и с заблокированным экраном") {
    OutlinedButton(
        onClick = {
          runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
          }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("1. Экономия батареи: найди «Очки ИИ» → «Не экономить»")
    }
    OutlinedButton(
        onClick = {
          runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
          }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text("2. Открыть настройки приложения")
    }
    Hint("В настройках приложения (Realme): «Расход батареи» → разреши фоновую активность и автозапуск. " +
        "В списке недавних приложений потяни карточку «Очки ИИ» вниз и нажми замок. С заблокированным " +
        "экраном пользуйся кнопками в уведомлении: «Спросить», «Фото», «Переводчик».")
  }

  Section("Камера очков напрямую") {
    Toggle("Включить функции камеры очков", cameraFeatures, onCameraFeatures)
    Hint("Сейчас очки отвечают «нужно обновление», поэтому эти функции выключены. Когда Meta обновит " +
        "компонент для сторонних приложений, включи — появятся «Проверить камеру», гид, фото и тачпад.")
    if (cameraFeatures) {
      if (initialized) RegistrationLine()
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRegister, modifier = Modifier.weight(1f)) { Text("Подключить") }
        OutlinedButton(onClick = onCameraPermission, modifier = Modifier.weight(1f)) { Text("Разрешить камеру") }
      }
      Toggle("Тачпад на дужке", controls) {
        controls = it
        Prefs.controls = it
        Brain.applyControls()
      }
      Text(
          if (controlsActive) "Связь с очками: активна ✅" else if (controls) "Связь с очками: подключаюсь…" else "Тачпад выключен",
          color = Color(0xFFBBBBBB),
          fontSize = 13.sp,
      )
    }
  }

  AiSettings()
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
        Hint("google/gemini-3.1-flash-lite — быстро и очень дёшево, понимает фото и звук. Умнее: " +
            "google/gemini-3.5-flash (список на openrouter.ai/models).")
      }
      "claude" -> {
        KeyField("Ключ Claude API (sk-ant-…)", claudeKey) { claudeKey = it; Prefs.claudeKey = it }
        ModelField(claudeModel) { claudeModel = it; Prefs.claudeModel = it }
        Hint("claude-haiku-5-5 — быстро и дёшево. Claude не слушает звук: диктофон и переводчик в режиме «ИИ» с ним не работают.")
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
private fun LangPicker(title: String, code: String, allowNone: Boolean, modifier: Modifier, onPick: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box(modifier) {
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
      Text("$title: ${if (code.isBlank()) "нет" else Langs.name(code)}", fontSize = 13.sp)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      if (allowNone) DropdownMenuItem(text = { Text("нет") }, onClick = { onPick(""); open = false })
      Langs.all.forEach { (c, n) -> DropdownMenuItem(text = { Text(n) }, onClick = { onPick(c); open = false }) }
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
  Text(text, fontSize = 13.sp, color = Gray)
}
