package com.artem.glassai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

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

// ======================= Экран =======================

@Composable
private fun Screen(onRegister: () -> Unit, onCameraPermission: () -> Unit) {
  val status by Brain.status.collectAsState()
  val busy by Brain.busy.collectAsState()
  val translatorOn by Brain.translatorOn.collectAsState()
  val photo by Brain.lastPhoto.collectAsState()
  val big by Brain.bigText.collectAsState()
  val log by Brain.log.collectAsState()
  val initialized by Glasses.initialized.collectAsState()

  var myLang by remember { mutableStateOf(Prefs.myLang) }
  var theirLang by remember { mutableStateOf(Prefs.theirLang) }

  Column(
      modifier =
          Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Очки ИИ", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD166))
    Text(status, fontSize = 16.sp, color = Color.White)

    // ---------- Подключение ----------
    Section("1. Подключение очков") {
      if (initialized) RegistrationLine()
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRegister, modifier = Modifier.weight(1f)) { Text("Подключить") }
        OutlinedButton(onClick = onCameraPermission, modifier = Modifier.weight(1f)) {
          Text("Разрешить камеру")
        }
      }
      Hint("Штатная съёмка («Hey Meta, take a photo», кнопка на дужке) работает как раньше: " +
          "приложение берёт камеру только на 2–3 секунды во время вопроса.")
    }

    // ---------- Помощник ----------
    Section("2. ИИ-помощник со зрением") {
      Button(
          onClick = { Brain.ask() },
          enabled = !busy || translatorOn,
          modifier = Modifier.fillMaxWidth().height(64.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD166), contentColor = Color.Black),
      ) {
        Text("🎤  Спросить: что я вижу?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
      Hint("Нажми и задай вопрос голосом: «Что это за фрукт?», «Переведи меню», «Сколько тут калорий?». " +
          "Можно молчать — тогда ИИ просто опишет, что перед тобой. Кнопка есть и в уведомлении.")
      photo?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Последний кадр",
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
      }
    }

    // ---------- Переводчик ----------
    Section("3. ИИ-переводчик") {
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
          modifier = Modifier.fillMaxWidth().height(56.dp),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (translatorOn) Color(0xFFE76F51) else Color(0xFF2A9D8F),
              ),
      ) {
        Text(if (translatorOn) "■  Выключить переводчик" else "🌐  Включить переводчик", fontSize = 17.sp)
      }
      OutlinedButton(onClick = { Brain.replyMine() }, modifier = Modifier.fillMaxWidth()) {
        Text("🗣  Мой ответ — перевести вслух через телефон")
      }
      if (big.isNotBlank()) {
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
          Text(big, fontSize = 28.sp, color = Color.Black, fontWeight = FontWeight.Medium)
        }
      }
      Hint("Собеседника слышишь в очках на своём языке. Свой ответ — кнопкой ниже: перевод прозвучит " +
          "из динамика телефона и появится крупно на экране, можно просто показать.")
    }

    OutlinedButton(onClick = { Brain.stopAll() }, modifier = Modifier.fillMaxWidth()) { Text("■ Стоп всё") }

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

@Composable
private fun RegistrationLine() {
  val state by Wearables.registrationState.collectAsState(initial = null)
  val scope = rememberCoroutineScope()
  var camOk by remember { mutableStateOf<Boolean?>(null) }
  LaunchedEffect(state) { scope.launch { camOk = runCatching { Glasses.isCameraAllowed() }.getOrNull() } }
  val reg = state?.toString() ?: "…"
  val regRu =
      when {
        reg.contains("UNREGISTER") -> "отключается"
        reg.contains("REGISTERED") -> "подключено ✅"
        reg.contains("REGISTERING") -> "подключается…"
        else -> "не подключено"
      }
  val cam = when (camOk) { true -> "разрешена ✅"; false -> "не разрешена"; null -> "…" }
  Text("Приложение в Meta AI: $regRu · Камера: $cam", color = Color(0xFFBBBBBB), fontSize = 14.sp)
}

@Composable
private fun AiSettings() {
  var provider by remember { mutableStateOf(Prefs.provider) }
  var claudeKey by remember { mutableStateOf(Prefs.claudeKey) }
  var claudeModel by remember { mutableStateOf(Prefs.claudeModel) }
  var geminiKey by remember { mutableStateOf(Prefs.geminiKey) }
  var geminiModel by remember { mutableStateOf(Prefs.geminiModel) }

  Section("Настройки ИИ") {
    Row(verticalAlignment = Alignment.CenterVertically) {
      RadioButton(selected = provider == "claude", onClick = { provider = "claude"; Prefs.provider = "claude" })
      Text("Claude", color = Color.White)
      Spacer(Modifier.padding(8.dp))
      RadioButton(selected = provider == "gemini", onClick = { provider = "gemini"; Prefs.provider = "gemini" })
      Text("Gemini", color = Color.White)
    }
    if (provider == "claude") {
      KeyField("Ключ Claude API (sk-ant-…)", claudeKey) { claudeKey = it; Prefs.claudeKey = it }
      OutlinedTextField(
          value = claudeModel,
          onValueChange = { claudeModel = it; Prefs.claudeModel = it },
          label = { Text("Модель") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      Hint("claude-haiku-5-5 — быстро и дёшево; claude-sonnet-5-5 — умнее, но медленнее и дороже.")
    } else {
      KeyField("Ключ Gemini API (AIza…)", geminiKey) { geminiKey = it; Prefs.geminiKey = it }
      OutlinedTextField(
          value = geminiModel,
          onValueChange = { geminiModel = it; Prefs.geminiModel = it },
          label = { Text("Модель") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      Hint("Ключ бесплатно берётся в Google AI Studio (aistudio.google.com → Get API key).")
    }
  }
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
