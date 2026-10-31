<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_generated.png" width="132" alt="Vocab App 图示">

  <h1>Vocab</h1>

  <p><strong>把单词、语法、文章阅读与英文写作整合在同一个 Android 学习空间。</strong></p>
  <p>本机优先、可自行设置 AI，适合创建自己的英文教材与复习流程。</p>

  <p>
    <a href="https://github.com/adam10000423-oss/Vocab/releases/latest"><img alt="最新版本" src="https://img.shields.io/github/v/release/adam10000423-oss/Vocab?display_name=tag&style=flat-square&label=Release&color=2196F3"></a>
    <a href="https://github.com/adam10000423-oss/Vocab/releases"><img alt="下载次数" src="https://img.shields.io/github/downloads/adam10000423-oss/Vocab/total?style=flat-square&label=Downloads&color=43A047"></a>
    <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/adam10000423-oss/Vocab?style=flat-square&label=License&color=FF7043"></a>
    <a href="https://github.com/adam10000423-oss/Vocab/actions/workflows/verify.yml"><img alt="建置状态" src="https://img.shields.io/github/actions/workflow/status/adam10000423-oss/Vocab/verify.yml?branch=main&style=flat-square&label=Build"></a>
    <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white">
  </p>

  <p>
    <a href="https://github.com/nicholasfox/Vocab-for-Android/releases/latest"><img alt="前往 Releases 下载最新版 APK" src="https://img.shields.io/badge/%E4%B8%8B%E8%BC%89%E6%9C%80%E6%96%B0%E7%89%88_APK-GitHub_Releases-1976D2?style=for-the-badge&logo=github&logoColor=white"></a>
  </p>

  <p>
    <a href="#download">下载与安装</a> ·
    <a href="#features">功能</a> ·
    <a href="#getting-started">开始使用</a> ·
    <a href="#ai">AI 设置</a> ·
    <a href="#development">开发</a> ·
    <a href="CHANGELOG.md">更新日志</a>
  </p>
</div>

---

> **本仓库是 [VocabGroup/Vocab-for-Android](https://github.com/VocabGroup/Vocab-for-Android) 的个人 fork，主要改动：**
>
> 1. **全应用简体中文**：界面、提示、AI Prompt、内置词典与 ECDICT 释义都已由繁体中文转换为简体中文（含常用台湾用语替换）。
> 2. **新增 AI 供应商**：DeepSeek、小米 MiMo、Agnes AI，以及可自行填写 Base URL 的「OpenAI 兼容（自定义）」，均可直接使用官方 API Key。
> 3. **新增 GitHub Actions 构建**：每次推送自动构建可安装的 debug APK，可在 Actions 的 `Build Simplified Chinese APK` 运行记录中下载 `vocab-zh-debug-apk`；推送 `zh-v*` 标签会自动发布到 Releases，可直接下载 APK。
>
> 当前 APK：[Releases · zh-v3.2.5](https://github.com/nicholasfox/Vocab-for-Android/releases/tag/zh-v3.2.5)
>
> debug 版包名为 `com.aistudio.vocabpulse.srsapp.zh`，可与官方繁体版共存。首次使用请在「设置 → AI 连接」中选择供应商并填入 API Key；自定义供应商还需填写 Base URL 与模型 ID。后续版本可直接覆盖安装（CI 会缓存同一个 debug 签名）。

---

Vocab 是使用 Kotlin、Jetpack Compose 与 Room 制作的开源 Android 英文学习 App。它不是预先塞满固定教材的课程平台，而是一个让用户整理自己的单词、语法、文章与作文纪录，再通过学习、测试和 AI 工具持续复习的个人学习系统。

主要学习数据保存在设备本机。AI 是选用功能：不设置 API Key，仍可使用单词卡、语法笔记、学习、测试、统计、备份等本机功能。

### 3.1 界面与操作

3.1 将现代化排版应用到主页、收藏清单、单词与语法编辑、导入、文章、作文、AI 和设置窗口。收藏列可直接点入目前页面的管理／学习／测试，其他操作由右侧「⋮」打开，拖动把手仍可调整顺序。

- 首页左上角的「单词／语法」胶囊可切换学习区域；其他页面显示目前页面的名称。
- 底部五个入口使用透明悬浮工作列，点击或左右滑动可切换页面。内容能延伸到工作列后方，清单尾端仍可卷动至完整显示。
- 「设置」主页列出各功能分类，点击后进入专属设置页，左上角返回键可回到设置主页。
- 「设置 → 外观」只提供跟随系统、亮色、暗色。背景、字体颜色与主色由 App 统一配置。
- 「设置 → 发音与提醒」可启用背景朗读与浮动窗口；窗口采用黑白配色，拖动可移动，翻面与下一张卡会保留位置，点击可回到 App。

<a id="download"></a>
## 下载与安装

### 直接安装 APK

1. 打开 **[最新版本下载页](https://github.com/adam10000423-oss/Vocab/releases/latest)**。
2. 在 Assets 下载 `Vocab-v版本号.apk`。
3. 在 Android 手机打开 APK。
4. 若系统询问，允许目前使用的浏览器或文件管理器「安装未知应用程序」。
5. 回到安装画面并确认安装。

需求与注意事项：

- 最低支持 Android 7.0（API 24）。
- 文件扫描使用 Google Play 服务提供的 ML Kit 扫描器；部分没有 Google Play 服务的设备可能无法使用该入口，但仍可从相簿或文件导入。
- 更新时必须安装使用相同签章的正式 APK，Android 才能保留既有数据并覆盖更新。
- Android 的安装确认画面属于系统安全机制，App 无法替用户自动按下「安装」。

### App 内更新

进入「设置 → 更新」即可：

- 检查 GitHub Releases 的最新版本。
- 选择是否自动检查，以及是否只在 Wi-Fi 下更新。
- 下载 APK 并打开 Android 系统安装器。
- 查看目前安装的版本号与更新日志。

<a id="features"></a>
## 功能总览

### 单词库与课程管理

- 使用「课程 → 文件夹 → 单词卡」整理教材。
- 创建、重新命名、搬移、删除及拖动排序文件夹与卡片。
- 单词卡可保存英文、中文解释、词性、音标、中英文例句与熟练状态。
- 依课程或文件夹浏览，并搜索自己已保存的单词。
- 从搜索结果直接跳到单词所在位置；编辑时可保留目前浏览位置。
- 内建数据质量检查，用来发现重复单词、缺漏字段、格式不一致与可能的拼字问题。

### 单词查询与导入

- 支持输入英文查中文，也可输入中文寻找英文结果。
- 可选择自动模式、Google 翻译、Cambridge、Wiktionary 与 Free Dictionary 等来源；来源是否可用仍受对方网站服务与连接限制影响。
- 查询结果可先用 AI 补齐音标、词性、解释与例句，再加入指定文件夹。
- 支持拍照、相簿、多页文件扫描与文件导入。
- 支持贴上公开网址、文字，以及可识别的 PDF、DOCX、TXT、CSV、TSV、JSON 等内容。
- 导入前可预览、编辑、AI 补齐、取消个别项目，再一次存入指定课程与文件夹。

### 单词学习与测试

- 翻卡学习与间隔复习（SRS）。
- 依「记得／不熟」更新学习状态，并可在结束后继续练习错题或不熟单词。
- 多种测试方式，包含选择、输入、配对、听力及口语练习。
- 口语可分为念单词与念例句，先播放示范，再录音识别与评分。
- 学习卡正面、背面可个别设置朗读字段、拼字与重复次数。
- 自动播放会等待目前卡片的语音完整结束才换页；手动切换时会停止上一张卡片的播放。

### 语法学习系统

- 语法数据使用独立的「课程 → 语法库 → 语法笔记」结构，不会混用单词文件夹。
- 一篇笔记可包含核心概念、多组句型、使用时机、注意事项、多个中英文例句、补充数据与多题练习。
- 编辑器以「基本、句型、题目、补充」分页整理长内容。
- 语法库支持搜索、拖动排序、学习进度与错题复习。
- 语法测试支持概念题、选择题、填空题，以及介系词、动词形式等句型挖空练习。
- 可从文字、图片、拍照、扫描、PDF、文件、AI 主题或 `.vocabshare` 创建语法数据。
- 多模态 AI 会优先保留教材内已有的句型与例句，识别不清的部分会标示或留空，保存前仍由用户预览确认。

### 互动文章阅读

- 可依指定单词文件夹生成文章，也能直接输入自定义主题、程度与文章类型。
- 支持贴上文字、拍照、文件扫描、相簿与文件导入文章。
- 文章创建后自动保存在本机，可从文章清单继续阅读或确认后删除。
- 点击文章中的英文单词会打开翻卡，显示发音、词性、中文解释与中英文例句。
- 尚未存在单词库的单词可即时查询完整数据，并选择要加入的文件夹。
- 可朗读文章，并从文章内容创建理解测试。

### 英文作文与写作检查

- 可自由输入作文，或从拍照、扫描、相簿与文件识别原稿。
- AI 检查以逐项问题呈现原句、建议修正、规则与繁体中文说明。
- 保留原始作文、修改后全文、批改项目、课程与标题。
- 每篇作文有独立详情页，可切换批改总览、原始作文、修改后作文及逐项批改。
- 用户可选择应用哪些修改；接受的问题可整理成「写作薄弱点」语法笔记，供后续学习与测试。

### AI 助手与自动整理

- AI 聊天可读取用户选择的多个文件夹与语法内容，协助解释、出题或整理学习数据。
- AI 写入动作会先显示待确认内容，用户确认后才修改本机数据。
- 可批次补齐单词、生成语法笔记、产生文章、检查作文与分析数据质量。
- 可创建多组 API 设置，个别测试不同供应商与模型。
- 支持 Google Gemini、OpenRouter、OpenAI 与 Anthropic Claude；实际可用模型依供应商账号与 API 规则而定。

### 学习纪录与设备整合

- 每日学习目标、待复习数、连续天数与学习日历。
- 学习进度、错题及熟练度会保存于本机数据库。
- 可设置学习提醒。
- 提供今日进度、每日单词、待复习与连续学习等 Android 桌面小工具。
- 界面支持手机直式与横式安全区，避免被系统导览列遮住。

### 备份、导出与分享数据

- 创建完整备份并在其他安装中还原。
- 导出 CSV 供试算表或其他工具使用。
- `.vocabshare` 用于携带可分享的学习内容，不包含 API Key、聊天纪录与私人学习纪录。
- 删除重要数据前会要求再次确认。

<a id="getting-started"></a>
## 建议的首次使用流程

1. **创建课程**：例如「高中英文第一册」或「TOEIC」。
2. **创建文件夹／语法库**：依课次、主题或来源分类。
3. **加入内容**：手动输入、查询、贴上文字，或使用拍照与扫描导入。
4. **检查预览**：确认 AI 或识别结果，修正不确定的文字后再保存。
5. **开始学习**：先使用翻卡创建印象，再进入测试。
6. **回顾错题**：完成测试后继续练习错题，或从学习日历查看累积状况。
7. **定期备份**：在「设置 → 数据」创建完整备份并保存在安全位置。

<a id="ai"></a>
## AI 连接设置

AI 功能需要用户自行提供支持供应商的 API Key，或自行部署选用的 Vocab Gateway。项目不附赠 API 额度，也不要求所有用户共用作者账号。

### 使用个人 API Key

1. 打开「设置 → AI」，找到「AI 连接」。
2. 选择供应商。
3. 依 App 内教学前往供应商官方网站创建 API Key。
4. 贴上 Key、选择模型并保存。
5. 在该组设置按「测试这组」，确认连接成功。
6. 若有多组 Key，可分别测试并设置优先顺序。

安全原则：

- 个人 API Key 会使用 Android Keystore 保护并保存在目前设备。
- API Key 不会写入完整备份、CSV、`.vocabshare`、聊天内容或 Git 保存库。
- 不要把 Key 贴进 Issue、聊天消息、屏幕截图或公开文件。
- API 的费用、速率限制、可用区域与内容政策由各供应商决定。

### 自行部署 Vocab Gateway

Gateway 使用 Node.js 内建模组，不需要第三方执行期套件。Node.js 需为 20 以上版本。

```powershell
cd server
Copy-Item .env.example .env
# 编辑 .env，填入要由服务器使用的供应商设置
npm test
npm start
```

Android Debug 版默认连到模拟器主机上的 `http://10.0.2.2:8787`。可在建置时替换：

```powershell
.\gradlew.bat assembleDebug -PBACKEND_BASE_URL=https://your-api.example.com
```

若公开部署 Gateway，请自行设置 HTTPS、来源限制、秘密管理、流量控管与监控。服务器已包含基本的请求大小限制、逾时、公开网址验证、重新导向限制及限流，但这些不能取代正式部署环境的安全措施。

## 隐私与安全

- 学习数据默认保存在设备上的 Room 数据库。
- Android 系统云端备份已停用；备份由用户在 App 内主动创建。
- AI 功能只在用户操作时，把完成该工作所需的文字或图片送往所选供应商。
- URL 导入只接受公开 HTTPS 位址，不会绕过登录、CAPTCHA 或网站访问控制。
- App 内更新只读取本项目公开的 GitHub Release；安装仍由 Android 系统验证与确认。
- 发现安全问题时，请依 [SECURITY.md](SECURITY.md) 私下回报，不要先创建公开 Issue。

<a id="development"></a>
## 从原始码建置

### 环境需求

- Android Studio（支持目前 Android Gradle Plugin）
- JDK 21
- Android SDK 36
- Git
- Node.js 20+（只有执行 Gateway 或 Gateway 测试时需要）

### 取得项目

```powershell
git clone https://github.com/adam10000423-oss/Vocab.git
cd Vocab
```

使用 Android Studio 打开项目根目录，等待 Gradle Sync 完成，选择 Android 设备后执行 `app`。

命令列验证：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Gateway 测试：

```powershell
cd server
npm test
```

### 主要技术

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose、Material 3 |
| 架构元件 | ViewModel、Kotlin Coroutines、Flow |
| 导览 | Navigation Compose |
| 本机数据 | Room、DataStore |
| 网络与序列化 | OkHttp、Retrofit、Moshi |
| 文件扫描 | Google Play services ML Kit Document Scanner |
| 测试 | JUnit、Robolectric、Roborazzi |
| 选用后端 | Node.js Vocab Gateway |
| CI/CD | GitHub Actions |

### 项目结构

```text
app/
├─ src/main/java/com/example/
│  ├─ data/          # Room、Repository、AI、辞典、导入与备份
│  ├─ ui/            # Compose 元件、画面、主题与导览
│  ├─ util/          # 文件解析、更新与共用工具
│  ├─ viewmodel/     # 画面状态与主要应用流程
│  └─ widgets/       # Android 桌面小工具
├─ src/main/res/     # 图示、字型、字符串与 Android 资源
└─ src/test/         # 单元与画面测试

server/
├─ src/              # 选用 AI Gateway
└─ test/             # Gateway 测试

.github/workflows/   # 验证与正式发布流程
```

## 正式版本与签章

Android 只允许使用相同 application ID 与相同签章的 APK 覆盖更新。正式发布前需更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`。

GitHub Actions 的 Release 工作流程需要以下 Repository Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

本机正式建置使用：

- `KEYSTORE_PATH`
- `STORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

推送与版本相同的标签（例如 `v2.10.2`）后，工作流程会执行测试、创建签章 APK，并把 APK 上传到 GitHub Releases。私钥、密码、API Key 与建置产物不得提交进 Git。

## 疑难排解

### 文件扫描显示不支持或无法打开

- 更新 Google Play 服务。
- 确认设备有可用的 Google Play 服务与足够保存空间。
- 若设备无法使用 ML Kit 扫描器，改用「拍照」、「照片」或「文件」导入。

### AI 测试失败

- 确认 API Key 没有多余空白且仍有效。
- 确认选择的模型存在，并且账号有权使用。
- 检查供应商的额度、速率限制、地区限制与服务状态。
- 分别测试每组 API 设置，以找出失效的 Key 或模型。

### 更新后无法覆盖安装

- 确认 APK 来自本项目的正式 Releases。
- 若曾安装其他签章的自行编译版，Android 会拒绝覆盖；请先导出备份，再移除旧版并安装正式版。
- 请勿在未备份数据前直接清除 App 数据或解除安装。

## 更新日志与回报

- [完整更新日志](CHANGELOG.md)
- [最新 GitHub Release](https://github.com/adam10000423-oss/Vocab/releases/latest)
- [问题回报](https://github.com/adam10000423-oss/Vocab/issues)
- [安全性回报方式](SECURITY.md)

## 授权

本项目采用 [MIT License](LICENSE)。
