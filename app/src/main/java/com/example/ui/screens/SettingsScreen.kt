package com.example.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import com.example.ui.components.ModernAlertDialog as AlertDialog
import com.example.ui.components.ModernButton as Button
import com.example.ui.components.ModernCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.example.ui.components.ModernTextField as OutlinedTextField
import com.example.ui.components.ModernOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.data.settings.AppSettings
import com.example.data.settings.ReminderTime
import com.example.data.api.AiProvider
import com.example.data.api.AiApiProfile
import com.example.util.TtsVoiceOption
import com.example.util.GitHubRelease
import com.example.util.GitHubUpdateManager
import com.example.util.InstallLaunchResult
import com.example.util.UpdateCheckResult
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBooleanChange: (String, Boolean) -> Unit,
    onIntChange: (String, Int) -> Unit,
    onReminderTimesChange: (List<ReminderTime>) -> Unit,
    onThemeChange: (String) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onDictionarySourceChange: (String) -> Unit,
    ttsVoices: List<TtsVoiceOption>,
    onTtsVoiceStyleChange: (String) -> Unit,
    onTtsVoiceNameChange: (String) -> Unit,
    onPreviewTtsVoice: () -> Unit,
    dataMessage: String?,
    apiKeyConfigured: Boolean,
    aiApiProfiles: List<AiApiProfile>,
    availableAiModels: List<String>,
    aiConnectionStatus: String?,
    onAiProviderChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiBaseUrlChange: (String) -> Unit,
    onAiChatStyleChange: (String) -> Unit,
    onAiPromptChange: (String) -> Unit,
    onResetAiPrompt: () -> Unit,
    onAiImagePromptChange: (String) -> Unit,
    onResetAiImagePrompt: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: (String) -> Unit,
    onRefreshAiModels: () -> Unit,
    onTestAiConnection: (String?) -> Unit,
    onExportBackup: (Uri) -> Unit,
    onExportCsv: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onClearAllData: () -> Unit,
    onBack: () -> Unit
) {
    var settingsPage by rememberSaveable { mutableStateOf("设置") }
    androidx.activity.compose.BackHandler(enabled = settingsPage != "设置") { settingsPage = "设置" }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf("尚未检查更新") }
    var availableRelease by remember { mutableStateOf<GitHubRelease?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var apiKeyInput by remember(settings.aiProvider) { mutableStateOf("") }
    var customModelInput by remember(settings.aiProvider) { mutableStateOf(false) }
    var showAiTutorial by remember { mutableStateOf(false) }
    var promptInput by remember(settings.aiWordPrompt) { mutableStateOf(settings.aiWordPrompt) }
    var imagePromptInput by remember(settings.aiImagePrompt) {
        mutableStateOf(settings.aiImagePrompt)
    }
    var showClearDataDialog by remember { mutableStateOf(false) }
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        downloadedApk?.takeIf(File::exists)?.let { apk ->
            when (val launch = GitHubUpdateManager.launchInstaller(context, apk)) {
                InstallLaunchResult.InstallerOpened -> updateStatus = "已打开系统安装画面"
                is InstallLaunchResult.PermissionRequired -> updateStatus = "请允许安装此来源后再按一次安装"
                is InstallLaunchResult.Failed -> updateStatus = "安装失败：${launch.message}"
            }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { it?.let(onExportBackup) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { it?.let(onExportCsv) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { it?.let(onImportBackup) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onBooleanChange("remindersEnabled", granted) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = AndroidSettings.canDrawOverlays(context)
        onBooleanChange("ttsFloatingOverlay", granted)
        if (granted) {
            onBooleanChange("ttsContinueInBackground", true)
        } else {
            Toast.makeText(context, "未取得浮动窗口权限，仍可单独使用背景朗读", Toast.LENGTH_LONG).show()
        }
    }

    fun openReminderPicker(index: Int? = null) {
        val current = index?.let { settings.reminderTimes.getOrNull(it) }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val picked = ReminderTime(hour, minute)
                val updated = if (index == null) {
                    settings.reminderTimes + picked
                } else {
                    settings.reminderTimes.mapIndexed { itemIndex, time ->
                        if (itemIndex == index) picked else time
                    }
                }
                onReminderTimesChange(updated.distinct().sorted())
            },
            current?.hour ?: 20,
            current?.minute ?: 0,
            true
        ).show()
    }

    fun checkForUpdates() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "正在检查 GitHub 最新版本…"
        coroutineScope.launch {
            when (val result = GitHubUpdateManager.checkForUpdate()) {
                is UpdateCheckResult.Available -> {
                    availableRelease = result.release
                    downloadedApk = GitHubUpdateManager.findDownloadedApk(context, result.release.version)
                    updateStatus = if (downloadedApk != null) {
                        "Vocab ${result.release.version} 已下载，可直接再次安装"
                    } else {
                        "发现 Vocab ${result.release.version}"
                    }
                }
                UpdateCheckResult.UpToDate -> {
                    availableRelease = null
                    downloadedApk = null
                    GitHubUpdateManager.cleanupInstalledDownloads(context)
                    updateStatus = "目前已是最新版本"
                }
                is UpdateCheckResult.Failed -> {
                    availableRelease = null
                    updateStatus = result.message
                }
            }
            updateBusy = false
        }
    }

    fun openInstaller(apk: File) {
        when (val launch = GitHubUpdateManager.launchInstaller(context, apk)) {
            InstallLaunchResult.InstallerOpened -> updateStatus = "已打开系统安装画面；若未完成，可回来再次安装"
            is InstallLaunchResult.PermissionRequired -> {
                updateStatus = "请先允许 Vocab 安装更新"
                installPermissionLauncher.launch(launch.intent)
            }
            is InstallLaunchResult.Failed -> updateStatus = "安装失败：${launch.message}"
        }
    }

    fun downloadAndInstall(release: GitHubRelease, forceDownload: Boolean = false) {
        if (updateBusy) return
        updateBusy = true
        updateStatus = if (forceDownload) {
            "正在重新下载 Vocab ${release.version}…"
        } else {
            "准备下载 Vocab ${release.version}…"
        }
        coroutineScope.launch {
            GitHubUpdateManager.downloadApk(
                context = context,
                release = release,
                wifiOnly = settings.wifiOnlyUpdates,
                forceDownload = forceDownload,
                onProgress = { progress -> updateStatus = "正在下载… $progress%" }
            ).onSuccess { apk ->
                downloadedApk = apk
                openInstaller(apk)
            }.onFailure {
                updateStatus = it.message ?: "下载更新失败"
            }
            updateBusy = false
        }
    }

    LaunchedEffect(settings.autoCheckUpdates) {
        if (settings.autoCheckUpdates && updateStatus == "尚未检查更新") checkForUpdates()
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("清空所有数据？") },
            text = { Text("会删除所有单词卡、文件夹、学习纪录、设置及本机 API Key，并回到首次设置。此动作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDataDialog = false
                    onClearAllData()
                }) { Text("确定全部清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAiTutorial) {
        AlertDialog(
            onDismissRequest = { showAiTutorial = false },
            title = { Text("AI 连接教程", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text("Google Gemini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("1. 登录 Google AI Studio。\n2. 创建或选择项目并产生 API Key。\n3. 回到 Vocab，选择 Google Gemini、贴上 Key、选模型后保存。\n4. 在该 Key 卡片按「测试这组」。")
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemini-api/docs/api-key"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                            Text("Gemini 官方教程")
                        }
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("前往取得 Gemini API Key") }
                    }
                    item {
                        Text("OpenRouter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("1. 登录 OpenRouter。\n2. 到 API Keys 创建 Key。\n3. 回到 Vocab，选择 OpenRouter、贴上 Key。\n4. 更新模型清单并选择模型，再测试该 Key。")
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/docs/quickstart"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                            Text("OpenRouter 官方教程")
                        }
                        Button(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("前往取得 OpenRouter API Key") }
                    }
                    item {
                        Text("DeepSeek / 小米 MiMo / Agnes AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("1. 到对应平台创建 API Key。\n2. 回到 Vocab，选择供应商并贴上 Key。\n3. 按「更新模型」选择模型，或手动输入模型 ID。\n4. 按「测试这组」确认连接。")
                    }
                    item {
                        Text("OpenAI 兼容（自定义）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("1. 在「API Base URL」填写服务商提供的地址，通常以 /v1 结尾。\n2. 贴上 API Key。\n3. 输入服务商提供的模型 ID。\n4. 仅支持文字模型时无法使用图片识别。")
                    }
                    item {
                        Text(
                            "安全提醒：API Key 只贴在 Vocab 的设置字段，不要传到聊天室、截图、GitHub 或分享网址。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAiTutorial = false }) { Text("完成") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 48.dp,
                title = { Text(settingsPage, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { if (settingsPage == "设置") onBack() else settingsPage = "设置" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(top = 6.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (settingsPage == "设置") {
                items(listOf(
                    "外观" to "跟随系统、亮色与暗色",
                    "学习" to "每日目标与复习",
                    "AI" to "连接、模型与补齐",
                    "扫描与文件" to "教材识别与导入",
                    "单词查询" to "辞典与翻译来源",
                    "发音与提醒" to "朗读、自动播放与通知",
                    "更新" to "版本与下载",
                    "数据" to "备份、导出与恢复"
                )) { (title, subtitle) ->
                    Surface(
                        onClick = { settingsPage = title },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (settingsPage == "外观") item {
                SettingsSection("主题") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("LIGHT" to "亮色", "DARK" to "暗色", "SYSTEM" to "系统").forEach { (value, label) ->
                            FilterChip(selected = settings.themeMode == value, onClick = { onThemeChange(value) }, label = { Text(label) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Text("画面与文字颜色随主题统一调整。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (settingsPage == "学习") item {
                SettingsSection("学习") {
                    NumberSetting("每日学习目标", settings.dailyGoalCards, 5, 5..100, suffix = " 张") {
                        onIntChange("dailyGoalCards", it)
                    }
                    NumberSetting("每日新卡上限", settings.dailyNewCardLimit, 5, 1..100) {
                        onIntChange("dailyNewCardLimit", it)
                    }
                    NumberSetting("每日复习上限", settings.dailyReviewLimit, 10, 10..500) {
                        onIntChange("dailyReviewLimit", it)
                    }
                    SettingSwitch("测试错题加入复习", settings.gameMistakesToReview) {
                        onBooleanChange("gameMistakesToReview", it)
                    }
                }
            }
            if (settingsPage == "AI") item {
                SettingsSection("AI 补齐") {
                    SettingSwitch("启用 AI 补齐", settings.aiEnabled) { onBooleanChange("aiEnabled", it) }
                    SettingSwitch("AI 结果应用前确认", settings.aiRequiresConfirmation) {
                        onBooleanChange("aiRequiresConfirmation", it)
                    }
                    SettingSwitch("使用我自己的 API Key", settings.usePersonalAiApi) {
                        onBooleanChange("usePersonalAiApi", it)
                    }
                    Text(
                        if (settings.usePersonalAiApi) {
                            "${AiProvider.from(settings.aiProvider).displayName} 直连"
                        } else {
                            "Vocab Gateway（未部署时无法使用）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it.take(4_000) },
                        label = { Text("自定义 Prompt") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onAiPromptChange(promptInput) },
                            enabled = promptInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存 Prompt")
                        }
                        TextButton(
                            onClick = {
                                promptInput = com.example.data.api.AiPromptDefaults.WORD_DETAILS
                                onResetAiPrompt()
                            }
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Text("重置")
                        }
                    }
                }
            }
            if (settingsPage == "AI") item {
                SettingsSection("AI 连接") {
                    val provider = AiProvider.from(settings.aiProvider)
                    val selectedProviderConfigured =
                        aiApiProfiles.any { it.provider == provider }
                    SettingDropdown(
                        label = "AI 供应商",
                        value = provider.name,
                        options = listOf(
                            AiProvider.GEMINI,
                            AiProvider.OPENROUTER,
                            AiProvider.OPENAI,
                            AiProvider.ANTHROPIC,
                            AiProvider.DEEPSEEK,
                            AiProvider.MIMO,
                            AiProvider.AGNES,
                            AiProvider.OPENAI_COMPATIBLE
                        ).map { it.name },
                        optionText = { AiProvider.from(it).displayName },
                        onSelected = {
                            customModelInput = false
                            onAiProviderChange(it)
                        }
                    )
                    OutlinedButton(
                        onClick = { showAiTutorial = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("查看 AI 连接教程") }
                    if (provider.keyUrl.isNotBlank()) {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Text("取得 API Key")
                        }
                    }
                    if (provider == AiProvider.OPENAI_COMPATIBLE) {
                        OutlinedTextField(
                            value = settings.aiBaseUrl,
                            onValueChange = onAiBaseUrlChange,
                            label = { Text("API Base URL") },
                            placeholder = { Text("https://api.example.com/v1") },
                            supportingText = {
                                Text("需兼容 OpenAI Chat Completions，例如 https://api.deepseek.com/v1")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text(if (selectedProviderConfigured) "贴上新的 API Key" else "贴上 API Key") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSaveApiKey(apiKeyInput) },
                            enabled = apiKeyInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text(if (selectedProviderConfigured) "加入另一把 Key" else "加入 Key") }
                    }
                    val modelOptions = remember(provider, availableAiModels) {
                        (provider.recommendedModels + availableAiModels)
                            .filter(String::isNotBlank)
                            .distinct()
                    }
                    SettingDropdown(
                        label = "AI 模型",
                        value = settings.aiModel,
                        options = modelOptions + CUSTOM_MODEL_OPTION,
                        optionText = {
                            if (it == CUSTOM_MODEL_OPTION) "自定义模型 ID…" else it
                        },
                        onSelected = {
                            if (it == CUSTOM_MODEL_OPTION) {
                                customModelInput = true
                            } else {
                                customModelInput = false
                                onAiModelChange(it)
                            }
                        }
                    )
                    SettingDropdown(
                        label = "聊天风格",
                        value = settings.aiChatStyle,
                        options = listOf("NORMAL", "RELAXED", "STRICT"),
                        optionText = {
                            when (it) {
                                "RELAXED" -> "轻松"
                                "STRICT" -> "严谨"
                                else -> "一般"
                            }
                        },
                        onSelected = onAiChatStyleChange
                    )
                    Text(
                        when (settings.aiChatStyle) {
                            "RELAXED" -> "语气自然轻松，也能聊英文学习以外的日常话题。"
                            "STRICT" -> "只处理英文学习、单词库与相关知识；不相关话题会礼貌拒绝，回答也会更审慎。"
                            else -> "一般助理语气，兼顾清楚、自然与实用性。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (customModelInput || settings.aiModel !in modelOptions) {
                        OutlinedTextField(
                            value = settings.aiModel,
                            onValueChange = onAiModelChange,
                            label = { Text("自定义模型 ID") },
                            supportingText = { Text("仅在清单没有需要的模型时使用") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefreshAiModels, enabled = selectedProviderConfigured) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("更新模型")
                        }
                        Button(onClick = { onTestAiConnection(null) }, enabled = selectedProviderConfigured && settings.aiModel.isNotBlank()) {
                            Text("测试连接")
                        }
                    }
                    aiConnectionStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                    if (aiApiProfiles.isNotEmpty()) {
                        Text("已保存的 API Key", fontWeight = FontWeight.Bold)
                        aiApiProfiles.forEachIndexed { index, profile ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${index + 1}. ${profile.provider.displayName}",
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(profile.model, style = MaterialTheme.typography.bodySmall)
                                        }
                                        IconButton(onClick = { onClearApiKey(profile.credentialId) }) {
                                            Icon(Icons.Default.DeleteForever, contentDescription = "移除此 API Key")
                                        }
                                    }
                                    OutlinedTextField(
                                        value = profile.maskedKey,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("API Key（已加密）") },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = { onTestAiConnection(profile.credentialId) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("测试这组 ${profile.provider.displayName} API")
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "同一供应商的 Key 会轮流使用；全部无法使用时才切换下一个供应商。Key 只会加密保存在此设备，不会放进备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (settingsPage == "扫描与文件") item {
                SettingsSection("扫描与文件") {
                    Text(
                        "拍照、照片与扫描页面会直接送到目前选择的多模态 AI 模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = imagePromptInput,
                        onValueChange = { imagePromptInput = it.take(4_000) },
                        label = { Text("图片识别 Prompt") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onAiImagePromptChange(imagePromptInput) },
                            enabled = imagePromptInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存 Prompt")
                        }
                        TextButton(
                            onClick = {
                                imagePromptInput =
                                    com.example.data.api.AiPromptDefaults.IMAGE_VOCABULARY_EXTRACTION
                                onResetAiImagePrompt()
                            }
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Text("重置")
                        }
                    }
                    SettingSwitch("导入前预览", settings.ocrPreviewBeforeImport) {
                        onBooleanChange("ocrPreviewBeforeImport", it)
                    }
                    NumberSetting("PDF 页数上限", settings.pdfPageLimit, 1, 1..50) {
                        onIntChange("pdfPageLimit", it)
                    }
                }
            }
            if (settingsPage == "单词查询") item {
                SettingsSection("单词查询") {
                    SettingDropdown(
                        label = "字典来源",
                        value = settings.dictionarySource,
                        options = listOf("AUTO", "ECDICT", "GOOGLE", "CAMBRIDGE", "WIKTIONARY", "FREE"),
                        optionText = {
                            when (it) {
                                "ECDICT" -> "ECDICT 英汉字典（离线）"
                                "GOOGLE" -> "Google 翻译"
                                "CAMBRIDGE" -> "剑桥英汉辞典"
                                "WIKTIONARY" -> "维基词典"
                                "FREE" -> "Free Dictionary（英文解释）"
                                else -> "自动选择（依序切换可用来源）"
                            }
                        },
                        onSelected = onDictionarySourceChange
                    )
                    Text(
                        "自动模式优先使用离线 ECDICT，查无结果才查询线上来源。ECDICT 中文释义已转为简体中文，不提供例句；中文反查请用 Google。ECDICT：github.com/skywind3000/ECDICT（MIT）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (settingsPage == "发音与提醒") item {
                SettingsSection("发音与提醒") {
                    SettingSwitch("翻卡后自动朗读", settings.autoSpeak) { onBooleanChange("autoSpeak", it) }
                    SettingSwitch("离开 App 后继续自动朗读", settings.ttsContinueInBackground) {
                        onBooleanChange("ttsContinueInBackground", it)
                        if (!it) onBooleanChange("ttsFloatingOverlay", false)
                    }
                    SettingSwitch("背景朗读时显示浮动窗口", settings.ttsFloatingOverlay) { enabled ->
                        if (!enabled) {
                            onBooleanChange("ttsFloatingOverlay", false)
                        } else if (AndroidSettings.canDrawOverlays(context)) {
                            onBooleanChange("ttsContinueInBackground", true)
                            onBooleanChange("ttsFloatingOverlay", true)
                        } else {
                            overlayPermissionLauncher.launch(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                    Text(
                        "背景朗读会以低干扰通知维持播放；浮动窗口只在离开 Vocab 时出现，正面显示英文、背面显示中文。首次启用需授予显示在其他应用程序上层的权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingDropdown(
                        label = "声音风格",
                        value = settings.ttsVoiceStyle,
                        options = listOf("NATURAL", "FEMALE", "MALE"),
                        optionText = {
                            when (it) {
                                "FEMALE" -> "女声风格（较明亮）"
                                "MALE" -> "男声风格（较低沉）"
                                else -> "自然原声"
                            }
                        },
                        onSelected = onTtsVoiceStyleChange
                    )
                    val voiceNames = remember(ttsVoices) {
                        listOf(AUTO_TTS_VOICE) + ttsVoices.map { it.name }
                    }
                    SettingDropdown(
                        label = "英文语音",
                        value = settings.ttsVoiceName.ifBlank { AUTO_TTS_VOICE },
                        options = voiceNames,
                        optionText = { name ->
                            if (name == AUTO_TTS_VOICE) {
                                "自动选择最佳英文语音"
                            } else {
                                ttsVoices.firstOrNull { it.name == name }?.let {
                                    "${it.label}（${it.name}）"
                                } ?: name
                            }
                        },
                        onSelected = {
                            onTtsVoiceNameChange(if (it == AUTO_TTS_VOICE) "" else it)
                        }
                    )
                    Text(
                        "实际可用声音由手机的语音引擎决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("学习卡正面自动朗读", fontWeight = FontWeight.Bold)
                    Text(
                        "自动播放会等正面内容全部念完才翻到背面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingSwitch("英文单词", settings.ttsFrontReadWord) { onBooleanChange("ttsFrontReadWord", it) }
                    SettingSwitch("拼音（逐字母拼读）", settings.ttsFrontSpellWord) { onBooleanChange("ttsFrontSpellWord", it) }
                    SettingSwitch("词性", settings.ttsFrontReadPartOfSpeech) { onBooleanChange("ttsFrontReadPartOfSpeech", it) }
                    NumberSetting("正面朗读次数", settings.ttsFrontRepetitions, 1, 1..3) {
                        onIntChange("ttsFrontRepetitions", it)
                    }
                    Text("学习卡背面自动朗读", fontWeight = FontWeight.Bold)
                    Text(
                        "需先打开「翻卡后自动朗读」；下列内容会依顺序组成一组播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingSwitch("英文单词", settings.ttsReadWord) { onBooleanChange("ttsReadWord", it) }
                    SettingSwitch("拼音（逐字母拼读）", settings.ttsSpellWord) { onBooleanChange("ttsSpellWord", it) }
                    SettingSwitch("中文解释", settings.ttsReadDefinition) { onBooleanChange("ttsReadDefinition", it) }
                    SettingSwitch("音标", settings.ttsReadPhonetic) { onBooleanChange("ttsReadPhonetic", it) }
                    SettingSwitch("词性", settings.ttsReadPartOfSpeech) { onBooleanChange("ttsReadPartOfSpeech", it) }
                    SettingSwitch("英文例句", settings.ttsReadExample) { onBooleanChange("ttsReadExample", it) }
                    SettingSwitch("中文例句", settings.ttsReadExampleTranslation) { onBooleanChange("ttsReadExampleTranslation", it) }
                    NumberSetting("每组朗读次数", settings.ttsGroupRepetitions, 1, 1..3) {
                        onIntChange("ttsGroupRepetitions", it)
                    }
                    Text("朗读速度 ${"%.1f".format(settings.speechRate)} 倍")
                    Slider(
                        value = settings.speechRate,
                        onValueChange = onSpeechRateChange,
                        valueRange = 0.5f..1.5f,
                        steps = 9
                    )
                    Button(onClick = onPreviewTtsVoice, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Text(" 试听目前声音")
                    }
                    SettingSwitch("每日学习提醒", settings.remindersEnabled) {
                        if (
                            it &&
                            Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onBooleanChange("remindersEnabled", it)
                        }
                    }
                    settings.reminderTimes.forEachIndexed { index, reminder ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    reminder.displayText(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { openReminderPicker(index) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑 ${reminder.displayText()}")
                                }
                                IconButton(
                                    onClick = {
                                        onReminderTimesChange(settings.reminderTimes.filterIndexed { itemIndex, _ -> itemIndex != index })
                                    },
                                    enabled = settings.reminderTimes.size > 1
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除 ${reminder.displayText()}")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { openReminderPicker() },
                        enabled = settings.reminderTimes.size < 12,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加提醒时间")
                    }
                }
            }
            if (settingsPage == "更新") item {
                SettingsSection("更新") {
                    SettingSwitch("自动检查更新", settings.autoCheckUpdates) {
                        onBooleanChange("autoCheckUpdates", it)
                    }
                    SettingSwitch("仅使用 Wi-Fi 更新", settings.wifiOnlyUpdates) {
                        onBooleanChange("wifiOnlyUpdates", it)
                    }
                    Text("目前版本 ${BuildConfig.VERSION_NAME}")
                    Text(updateStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = ::checkForUpdates,
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("检查更新")
                    }
                    availableRelease?.let { release ->
                        val cachedApk = downloadedApk?.takeIf(File::exists)
                        if (cachedApk == null) {
                            Button(
                                onClick = { downloadAndInstall(release) },
                                enabled = !updateBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("下载并安装 Vocab ${release.version}") }
                        } else {
                            Button(
                                onClick = { openInstaller(cachedApk) },
                                enabled = !updateBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("再次安装 Vocab ${release.version}") }
                            OutlinedButton(
                                onClick = { downloadAndInstall(release, forceDownload = true) },
                                enabled = !updateBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("重新下载安装档") }
                        }
                        if (release.notes.isNotBlank()) {
                            Text("更新日志", fontWeight = FontWeight.Bold)
                            Text(
                                release.notes
                                    .replace(Regex("(?m)^#{1,6}\\s*"), "")
                                    .replace("**", "")
                                    .take(4_000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "安装取消或失败时会保留这份 APK，可直接再次安装；重新下载或成功升级后会自动清除旧档。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (settingsPage == "数据") item {
                SettingsSection("数据") {
                    Text("单词与学习数据默认只保存在本机。")
                    Button(
                        onClick = { backupLauncher.launch("vocab-backup.json") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("创建完整备份") }
                    Button(
                        onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("恢复备份") }
                    Button(
                        onClick = { csvLauncher.launch("vocab-cards.csv") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出 CSV") }
                    Button(
                        onClick = { showClearDataDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text(" 清空所有数据并从 0 开始")
                    }
                    dataMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
}

private const val CUSTOM_MODEL_OPTION = "__custom_model__"
private const val AUTO_TTS_VOICE = "__auto_tts_voice__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    label: String,
    value: String,
    options: List<String>,
    optionText: (String) -> String = { it },
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = optionText(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int,
    step: Int,
    range: IntRange,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - step).coerceIn(range)) }) {
            Icon(Icons.Default.Remove, contentDescription = "减少$label")
        }
        Text("$value$suffix", fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange((value + step).coerceIn(range)) }) {
            Icon(Icons.Default.Add, contentDescription = "增加$label")
        }
    }
}
