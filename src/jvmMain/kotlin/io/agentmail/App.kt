@file:OptIn(ExperimentalMaterial3Api::class)

package io.agentmail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF09101F)
private val Panel = Color(0xFF111B2E)
private val Raised = Color(0xFF18243A)
private val Electric = Color(0xFF77D4FF)
private val Mint = Color(0xFF68E0B8)
private val Warm = Color(0xFFFFC36A)
private val Danger = Color(0xFFFF7D8D)
private val Muted = Color(0xFF93A4BD)

/** Корневой экран настройки, запуска и наблюдения за почтовым агентом. */
@Composable
fun AgentMailApp(controller: AppController) {
    val controllerState by controller.state.collectAsState()
    val monitor by controller.snapshot.collectAsState()
    var settings by remember { mutableStateOf(controllerState.settings) }
    var mailPassword by remember { mutableStateOf("") }
    var llmApiKey by remember { mutableStateOf("") }
    var telegramToken by remember { mutableStateOf("") }

    LaunchedEffect(controllerState.settings) {
        settings = controllerState.settings
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Electric,
            secondary = Mint,
            background = Ink,
            surface = Panel,
            surfaceVariant = Raised,
            error = Danger,
            onPrimary = Ink,
            onBackground = Color(0xFFF3F7FF),
            onSurface = Color(0xFFF3F7FF),
        )
    ) {
        Row(Modifier.fillMaxSize().background(Ink)) {
            BrandRail(monitor.status)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = 34.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Настройка агента", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Письма остаются локальными до точного совпадения тега. В модель отправляются только найденные упоминания.",
                    color = Muted,
                    fontSize = 14.sp,
                )

                SettingsCard("01", "Корпоративная почта") {
                    OutlinedButton(
                        onClick = {
                            settings = settings.copy(
                                imapUsername = settings.imapUsername.ifBlank { settings.email },
                                imapHost = "imap.gmail.com",
                                imapPort = 993,
                                useStartTls = false,
                                folder = "INBOX",
                            )
                        },
                    ) { Text("Применить Google Workspace") }
                    FormRow {
                        Field(
                            "Email",
                            settings.email,
                            { value ->
                                val previousEmail = settings.email
                                // Логин следует за email, пока пользователь не изменил его отдельно.
                                settings = settings.copy(
                                    email = value,
                                    imapUsername = if (
                                        settings.imapUsername.isBlank() || settings.imapUsername == previousEmail
                                    ) value else settings.imapUsername,
                                )
                            },
                            "name@company.io",
                        )
                        Field(
                            "Логин",
                            settings.imapUsername,
                            { settings = settings.copy(imapUsername = it) },
                            "name@company.io",
                        )
                    }
                    FormRow {
                        Field("IMAP host", settings.imapHost, { settings = settings.copy(imapHost = it) }, "imap.gmail.com")
                        Field(
                            "Port",
                            settings.imapPort.toString(),
                            { value -> value.toIntOrNull()?.let { settings = settings.copy(imapPort = it) } },
                            "993",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Field("Folder", settings.folder, { settings = settings.copy(folder = it) }, "INBOX")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.useStartTls,
                            onCheckedChange = { settings = settings.copy(useStartTls = it) },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("STARTTLS вместо IMAPS", color = Muted)
                    }
                    SecretField("Пароль или app password", mailPassword) { mailPassword = it }
                }

                SettingsCard("02", "Правило упоминания") {
                    FormRow {
                        Field("Ваш тег", settings.tag, { settings = settings.copy(tag = it) }, "@dmitry.bykov")
                        Field(
                            "Проверять каждые, мин",
                            settings.pollIntervalMinutes.toString(),
                            { value -> value.toIntOrNull()?.let { settings = settings.copy(pollIntervalMinutes = it) } },
                            "2",
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    Text("При первом подключении старые письма не пересылаются.", color = Muted, fontSize = 13.sp)
                }

                SettingsCard("03", "Модель через Koog") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.llmProvider == LlmProviderType.QWEN,
                            onClick = { settings = settings.copy(llmProvider = LlmProviderType.QWEN) },
                            label = { Text("Qwen / DashScope") },
                        )
                        FilterChip(
                            selected = settings.llmProvider == LlmProviderType.CUSTOM,
                            onClick = { settings = settings.copy(llmProvider = LlmProviderType.CUSTOM) },
                            label = { Text("Корпоративная") },
                        )
                    }
                    if (settings.llmProvider == LlmProviderType.QWEN) {
                        FormRow {
                            ChoiceField(
                                label = "Регион",
                                options = listOf("International", "China"),
                                selected = settings.qwenRegion,
                                onSelect = { settings = settings.copy(qwenRegion = it) },
                            )
                            ChoiceField(
                                label = "Модель",
                                options = listOf("qwen-plus", "qwen-plus-latest", "qwen-flash"),
                                selected = settings.qwenModel,
                                onSelect = { settings = settings.copy(qwenModel = it) },
                            )
                        }
                    } else {
                        Field(
                            "Base URL",
                            settings.customBaseUrl,
                            { settings = settings.copy(customBaseUrl = it) },
                            "https://llm.company.io",
                        )
                        FormRow {
                            Field(
                                "Chat path",
                                settings.customChatPath,
                                { settings = settings.copy(customChatPath = it) },
                                "v1/chat/completions",
                            )
                            Field(
                                "Model ID",
                                settings.customModel,
                                { settings = settings.copy(customModel = it) },
                                "company-chat",
                            )
                        }
                    }
                    SecretField("API Key", llmApiKey) { llmApiKey = it }
                }

                SettingsCard("04", "Доставка в Telegram") {
                    Field(
                        "Chat ID",
                        settings.telegramChatId,
                        { settings = settings.copy(telegramChatId = it) },
                        "123456789 или -100...",
                    )
                    SecretField("Bot token", telegramToken) { telegramToken = it }
                }

                controllerState.notice?.let {
                    Notice(it, controllerState.noticeIsError)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = {
                            controller.testConnections(settings, enteredSecrets(mailPassword, llmApiKey, telegramToken))
                        },
                        enabled = !controllerState.busy && monitor.status != MonitorStatus.RUNNING,
                    ) {
                        if (controllerState.busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Проверить")
                    }
                    OutlinedButton(
                        enabled = monitor.status != MonitorStatus.RUNNING && monitor.status != MonitorStatus.STARTING,
                        onClick = {
                        if (controller.save(settings, enteredSecrets(mailPassword, llmApiKey, telegramToken))) {
                            mailPassword = ""
                            llmApiKey = ""
                            telegramToken = ""
                        }
                        },
                    ) { Text("Сохранить") }
                    if (monitor.status == MonitorStatus.RUNNING || monitor.status == MonitorStatus.STARTING) {
                        Button(onClick = controller::stop, colors = ButtonDefaults.buttonColors(containerColor = Danger)) {
                            Text("Остановить", color = Ink)
                        }
                    } else if (monitor.status == MonitorStatus.STOPPING) {
                        Button(onClick = {}, enabled = false) { Text("Останавливается...") }
                    } else {
                        Button(onClick = {
                            controller.start(settings, enteredSecrets(mailPassword, llmApiKey, telegramToken))
                        }) { Text("Запустить агента") }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            StatusPanel(monitor, controllerState.hasSecrets)
        }
    }
}

@Composable
private fun BrandRail(status: MonitorStatus) {
    Column(
        modifier = Modifier.width(82.dp).fillMaxHeight().background(Panel).padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).background(Electric, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text("@", color = Ink, fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(10.dp).background(statusColor(status), CircleShape))
        Spacer(Modifier.height(12.dp))
        Text("AM", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPanel(snapshot: MonitorSnapshot, hasSecrets: Boolean) {
    Column(
        modifier = Modifier.width(300.dp).fillMaxHeight().background(Panel)
            .verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("AGENT STATUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(statusColor(snapshot.status), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(statusTitle(snapshot.status), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = Raised)
        Metric("Проверено", snapshot.checked.toString())
        Metric("Упоминаний", snapshot.matched.toString())
        Metric("Отправлено", snapshot.sent.toString(), Mint)
        Metric("Последняя проверка", snapshot.lastCheck?.let {
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
        } ?: "—")
        HorizontalDivider(color = Raised)
        Text("СЕКРЕТЫ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(if (hasSecrets) "Сохранены в OS keyring" else "Ещё не сохранены", color = if (hasSecrets) Mint else Warm)
        snapshot.lastError?.let { Notice(it, true) }
        Text("АКТИВНОСТЬ", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        if (snapshot.events.isEmpty()) Text("Событий пока нет", color = Muted, fontSize = 13.sp)
        snapshot.events.forEach { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
        Text("ИСТОРИЯ TELEGRAM", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        if (snapshot.deliveries.isEmpty()) {
            Text("Отправлений пока нет", color = Muted, fontSize = 13.sp)
        }
        snapshot.deliveries.forEach { delivery ->
            DeliveryHistoryItem(delivery)
        }
        Spacer(Modifier.height(8.dp))
        Text("Работает, пока приложение открыто и компьютер не спит.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun DeliveryHistoryItem(delivery: DeliveryRecord) {
    val color = when (delivery.status) {
        DeliveryStatus.DELIVERED -> Mint
        DeliveryStatus.FAILED -> Danger
        DeliveryStatus.ATTEMPTING, DeliveryStatus.UNKNOWN -> Warm
    }
    val status = when (delivery.status) {
        DeliveryStatus.DELIVERED -> "Доставлено"
        DeliveryStatus.FAILED -> "Ошибка, разрешён повтор"
        DeliveryStatus.ATTEMPTING -> "Отправляется"
        DeliveryStatus.UNKNOWN -> "Без автоповтора"
    }
    Column(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(delivery.subject.ifBlank { "Без темы" }.take(45), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(status, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsCard(index: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(index, color = Electric, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun FormRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun RowScope.Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Field(label, value, onChange, placeholder, Modifier.weight(1f), keyboardType)
}

@Composable
private fun ColumnScope.Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Field(label, value, onChange, placeholder, Modifier.fillMaxWidth(), keyboardType)
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text("Оставьте пустым, чтобы использовать сохранённый") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun RowScope.ChoiceField(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) })
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) Danger else Mint,
        modifier = Modifier.fillMaxWidth().background(
            color = (if (isError) Danger else Mint).copy(alpha = 0.09f),
            shape = RoundedCornerShape(10.dp),
        ).padding(12.dp),
        fontSize = 13.sp,
    )
}

@Composable
private fun Metric(label: String, value: String, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = color, fontWeight = FontWeight.Medium)
    }
}

/** Пустая форма означает, что ранее сохранённые секреты менять не нужно. */
private fun enteredSecrets(mail: String, llm: String, telegram: String): Secrets? =
    if (mail.isBlank() && llm.isBlank() && telegram.isBlank()) null else Secrets(mail, llm, telegram)

private fun statusColor(status: MonitorStatus): Color = when (status) {
    MonitorStatus.RUNNING -> Mint
    MonitorStatus.ERROR -> Danger
    MonitorStatus.STARTING, MonitorStatus.STOPPING -> Warm
    MonitorStatus.STOPPED -> Muted
}

private fun statusTitle(status: MonitorStatus): String = when (status) {
    MonitorStatus.RUNNING -> "Работает"
    MonitorStatus.STARTING -> "Запускается"
    MonitorStatus.STOPPING -> "Останавливается"
    MonitorStatus.ERROR -> "Ошибка"
    MonitorStatus.STOPPED -> "Остановлен"
}
