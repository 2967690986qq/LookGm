# LookGm - 游戏对局评分助手 (v1.2.0)

跨平台（Android/iOS）游戏对局评分助手，通过屏幕实时视觉分析 + AI识别游戏状态、计算评分，并通过语音播报指引玩家。

> **核心设计原则**：无进程注入 · 可扩展插件架构 · 本地数据处理 · 防检测模式 · 用户登录 + 本地存储

---

## 一、功能总览

| 模块 | 说明 |
|------|------|
| 🔐 **认证登录** | 支持微信/QQ授权登录 + 游客模式，所有用户信息存在本地 |
| 🎮 **王者荣耀适配** | 5分路（对抗路/打野/中路/游走/发育路）· 评分任务系统 |
| 📊 **5维度评分引擎** | 输出 · 生存 · 团战 · 发育 · KDA · 满分 16.0 |
| 🏆 **4级评级** | 铜牌评分 · 银牌评分 · 金牌评分 · 顶级评分 |
| 🤖 **AI API 服务** | 支持 DeepSeek / Qwen / GPT / Claude / Gemini / **智谱 GLM** / 自定义 Provider |
| 🪟 **悬浮窗** | 系统级悬浮窗实时展示评分与任务 |
| 🎙 **语音播报** | 评分/任务/警告三类播报，支持系统 TTS / 本地 TTS |
| 🎥 **屏幕监测与录屏** | 实时屏幕采集 + OCR + 对象检测 + 录屏保存 |
| 👤 **个人中心** | 昵称修改 · 对局次数 · 偏好设置 · 清理本地数据 |
| ⚙️ **偏好设置** | 语音 · 悬浮窗 · 监测 · 录屏 · AI · 防检测 全部可配置 |

---

## 二、整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    UI 层 (React Native)                       │
│  [LoginPage] 登录 · [HomePage] 主界面 · 评分展示 · 任务       │
│  [ProfilePage] 个人中心 · 数据清理 · 退出登录                  │
│  [AISettingsPage] AI 配置 · API Key · 模型选择 · 连接测试     │
│  [SettingsPage] 语音/悬浮窗/监测偏好配置                      │
│  [RecordingHistoryPage] 录屏记录 · 清空                        │
└────────────────────────────────┬──────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────┐
│                 状态管理层 (AppStore)                          │
│  全局状态（page/user/gameId/role/score/capture/recording...）│
│  事件总线 · 服务协作 · 统一对外 API                            │
└────────────────────────────────┬──────────────────────────────┘
                                 │
       ┌─────────────────────────┼─────────────────────────┐
       ▼                         ▼                         ▼
┌─────────────────┐    ┌─────────────────────────┐   ┌─────────────────┐
│  游戏适配层     │    │   核心服务层              │   │   防检测层       │
│  (Plugin)       │    │  (Core Services)        │   │  (Anti-Detect)  │
│                 │    │                         │   │                 │
│ • 王者荣耀      │    │ • AuthService 认证      │   │ • 进程隔离       │
│ • (更多游戏)    │    │ • AIApiService 多模型   │   │ • 模式随机化     │
│                 │    │ • ScoringEngine 评分    │   │ • 隐私模式       │
│                 │    │ • Voice 语音播报        │   │ • 操作日志       │
│                 │    │ • FloatingWindow 悬浮窗 │   │                 │
│                 │    │ • ScreenMonitoring 监测 │   │                 │
│                 │    │ • ScreenCapture 采集    │   │                 │
└─────────────────┘    └─────────────────────────┘   └─────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────┐
│              原生层 (Android/iOS 原生模块)                   │
│   MediaProjection / ScreenCaptureKit / TTS / 悬浮窗         │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、目录结构

```
LookGm/
├── package.json               # 项目配置 + 依赖
├── index.js                   # RN 入口
├── babel.config.js            # Babel 配置
├── metro.config.js            # Metro 打包配置
├── tsconfig.json              # TypeScript 配置
├── README.md                  # 架构文档
└── src/
    ├── App.tsx                # 主应用 UI (登录/主界面/个人中心/AI配置/偏好/录屏)
    ├── types/                 # 类型定义
    │   ├── game.ts           # 分路定义 · GameState
    │   ├── scoring.ts        # 评分规则 · 评分任务 · 加减分
    │   ├── voice.ts          # 语音消息类型
    │   └── index.ts
    ├── core/                  # 核心服务
    │   ├── auth/
    │   │   └── AuthService.ts          # 认证 · 微信/QQ/游客登录 · 偏好 · 持久化
    │   ├── ai-api/
    │   │   └── AIApiService.ts         # 多模型 AI API (DeepSeek/Qwen/GPT/Claude/Gemini/Custom)
    │   ├── scoring-engine/
    │   │   └── ScoringEngine.ts        # 5维度评分引擎 + 评分任务
    │   ├── floating-window/
    │   │   └── FloatingWindowService.ts # 系统级悬浮窗服务
    │   ├── voice-guide/
    │   │   └── VoiceGuideService.ts     # 游戏阶段语音播报
    │   ├── voice-communication/
    │   │   └── VoiceCommunicationService.ts # TTS 播报 · STT 语音识别
    │   ├── screen-capture/
    │   │   └── ScreenCaptureService.ts  # 屏幕帧采集
    │   ├── screen-monitoring/
    │   │   └── ScreenMonitoringService.ts # 实时监测 + OCR + 录屏保存
    │   ├── ai-analysis/
    │   │   └── AIAnalysisService.ts    # 游戏 UI 视觉分析
    │   ├── anti-detection/
    │   │   └── AntiDetectionService.ts  # 防检测服务
    │   └── index.ts
    ├── game-adapters/         # 游戏适配插件
    │   ├── base/
    │   │   ├── GameAdapter.ts
    │   │   └── AdapterRegistry.ts
    │   ├── king-of-glory/
    │   │   ├── KingOfGloryAdapter.ts
    │   │   └── ScoringCriteria.ts
    │   └── index.ts
    └── store/
        └── AppStore.ts        # 应用状态管理 + 服务协调
```

---

## 四、核心模块详解

### 4.1 AuthService - 用户认证服务

**核心能力**：
- 微信授权登录 / QQ 授权登录 / 游客模式三种登录方式
- 登录后用户信息写入本地存储
- 支持用户偏好（theme / language / 语音/悬浮窗/监测开关）的持久化
- 退出登录：清理会话 Token + 重置应用状态

**数据结构**（[AuthService.ts](file:///c:/Users/Administrator/Desktop/LookGm/src/core/auth/AuthService.ts)）：

```typescript
interface UserProfile {
  id: string;
  nickname: string;
  avatar?: string;
  provider: 'wechat' | 'qq' | 'guest';
  openId?: string;
  unionId?: string;
  loginTime: string;
  level: number;
  totalScore: number;
  gamesPlayed: number;
  preferences: UserPreferences;
}

interface UserPreferences {
  theme: 'light' | 'dark';
  language: 'zh-CN' | 'en-US';
  autoLogin: boolean;
  enableVoice: boolean;
  enableFloatingWindow: boolean;
  enableRealTimeMonitoring: boolean;
  enableAIAnalysis: boolean;
  fps: number;
  bitrate: number;
  enableAntiDetection?: boolean;
}
```

**持久化策略**：
- 以 `localStorage` polyfill 为底层存储
- Key 前缀 `lookgm_` 如 `lookgm_currentUser`、`lookgm_sessionToken`
- 登录成功后立即写入，启动时读取并恢复

**对外事件**：`login` · `logout` · `profile-updated` · `preferences-updated` · `auto-login`

---

### 4.2 AIApiService - 多模型 AI API 服务

**核心能力**：
- 同时管理多 Provider 配置（DeepSeek / Qwen / GPT / Claude / Gemini / 自定义）
- 每个 Provider 独立配置：API Key · Base URL · 模型名 · 最大 Token · 启用开关
- 保存全部配置到本地（不会上传）
- 提供**连接测试**（向 AI 发送测试消息并报告响应时间）
- 提供**对话测试**（用户可在 AI 配置页直接发送问题查看回复）

**Provider 配置结构**（[AIApiService.ts](file:///c:/Users/Administrator/Desktop/LookGm/src/core/ai-api/AIApiService.ts)）：

```typescript
type AIProvider = 'deepseek' | 'qwen' | 'gpt' | 'claude' | 'gemini' | 'zhipu' | 'custom' | 'none';

interface AIProviderConfig {
  provider: AIProvider;
  name: string;
  apiKey: string;         // 本地加密存储
  baseUrl: string;
  model: string;
  maxTokens?: number;
  temperature?: number;
  systemPrompt?: string;
  enabled: boolean;
}
```

**默认推荐模型**：
| Provider | 默认 API URL | 默认模型 |
|----------|-------------|----------|
| DeepSeek | `https://api.deepseek.com/v1/chat/completions` | `deepseek-chat` |
| Qwen | `https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation` | `qwen-plus` |
| GPT | `https://api.openai.com/v1/chat/completions` | `gpt-4o-mini` |
| Claude | `https://api.anthropic.com/v1/messages` | `claude-3-haiku-20240307` |
| Gemini | `https://generativelanguage.googleapis.com/v1/models` | `gemini-1.5-flash` |
| **智谱 GLM** | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-4-flash` |
| Custom | 用户自定义 | 用户自定义 |

**请求封装**：
- DeepSeek / GPT / 智谱 GLM / Custom → 统一 OpenAI 兼容 body
- Qwen → DashScope body
- Claude → Anthropic body（带 version header）
- Gemini → 专用 URL 拼接 + JSON body
- 所有请求自动计算 `responseTime`（毫秒）

**UI 能力**：
- AI 配置页以 Provider 卡片列出所有选项，每个卡片独立编辑/测试/对话
- 顶部"选用"按钮切换当前使用的 Provider
- AI 状态会同步在个人中心展示：`✅ 已配置` / `⚠️ 未配置 API Key`

**对外事件**：`provider-updated` · `provider-changed` · `history-cleared` · `chat-completed` · `test-completed` · `all-data-cleared`

---

### 4.3 ScoringEngine - 5维度评分引擎

**评分维度**（满分 16.0）：

| 维度 | 权重 | 说明 |
|------|------|------|
| 输出 (damage) | 2.5 | 伤害输出统计 |
| 生存 (survival) | 2.0 | 基于死亡次数反向计算 |
| 团战 (teamfight) | 2.0 | (击杀 + 助攻) / 全队击杀 |
| 发育 (farm) | 2.0 | 金币进度 |
| KDA | 2.5 | 击杀·死亡·助攻综合 |

**4级评级划分**：

| 分数区间 | 评级 | 图标 |
|---------|------|------|
| 14.0 - 16.0 | 顶级评分 | 👑 |
| 12.0 - 14.0 | 金牌评分 | 🥇 |
| 9.0 - 12.0 | 银牌评分 | 🥈 |
| 7.0 - 9.0 | 铜牌评分 | 🥉 |
| < 7.0 | 暂无评级 | — |

**评分任务系统**：
- 每个分路定义铜/银/金/顶级 4 档加分任务
- 每个分路定义扣分操作（多次死亡 / 零击杀 / 低参团率）
- 评分引擎在计算基础分后叠加加分项、减去扣分项得到最终分
- 完成任务列表在首页 UI 中独立展示

---

### 4.4 FloatingWindowService - 系统级悬浮窗

**能力**：
- **独立开关**：可在首页/偏好设置启用/关闭
- **展示内容**：实时评分 + 评级 + 当前分路任务
- **UI 可控**：位置（左上/右上/左下/右下/居中）· 大小（小/中/大）· 透明度
- **自动隐藏**：配置时长后自动最小化
- **拖拽**：通过 setCustomPosition 手动设置坐标
- **模式切换**：`score-display`（仅分数） / `task-guide`（任务） / `chat`（对话） / `minimized`

**核心配置**：

```typescript
interface WindowConfig {
  enabled: boolean;
  position: WindowPosition;
  size: WindowSize;
  mode: WindowMode;
  opacity: number;       // 0.1 ~ 1.0
  draggable: boolean;
  showScore: boolean;
  showTasks: boolean;
  showChat: boolean;
  autoHide: boolean;
  autoHideDelay: number; // ms
  colorScheme: 'dark' | 'light' | 'system';
}
```

**对外事件**：`started` · `stopped` · `visibility-changed` · `minimized-changed` · `position-changed` · `size-changed` · `mode-changed` · `opacity-changed` · `config-updated` · `score-updated` · `tasks-updated` · `chat-updated` · `permission-required` · `all-data-cleared`

---

### 4.5 VoiceCommunicationService - 语音沟通

**两路能力**：

| 方向 | 能力 | 说明 |
|------|------|------|
| 🗣 **TTS (Text to Speech)** | 播报评分/任务/警告 | 优先使用系统 SpeechSynthesis，失败时降级静默 |
| 🎙 **STT (Speech to Text)** | 识别用户语音指令 | 可触发"查看分数" · "查看任务" · "关闭" · "设置音量" · "AI分析" 等命令 |

**核心配置**：

```typescript
interface VoiceConfig {
  enabled: boolean;
  mode: 'tts' | 'stt' | 'conversation';
  engine: 'system' | 'online' | 'custom';
  language: string;           // zh-CN / en-US ...
  rate: number;               // 0.5 ~ 2.0
  pitch: number;              // 0.5 ~ 2.0
  volume: number;             // 0.0 ~ 1.0
  gender: 'male' | 'female' | 'neutral';
  autoSpeakScore: boolean;
  autoSpeakTasks: boolean;
  autoSpeakWarnings: boolean;
  enableWakeWord: boolean;
  wakeWord: string;
}
```

**消息队列**：
- 所有播报通过消息队列串行处理，避免打断
- 每条消息有 type：`score`（评分变更）· `task`（任务提示）· `warning`（警告）· `info`（一般信息）
- 支持 `stopSpeaking()` 立即中断全部

**语音命令解析**：
- 命中"分数/评分" → show-score
- 命中"任务" → show-tasks
- 命中"关闭/停止" → close
- 命中数字 → set-volume (volume = N/100)
- 命中"分析/建议" → analyze（调用 AI 分析服务）
- 默认 → chat（交由 AI 对话）

**对外事件**：`message-queued` · `speaking-started` · `speaking-completed` · `speaking-error` · `speaking-stopped` · `listening-started` · `listening-stopped` · `interim-text` · `voice-input` · `command-detected` · `wake-word-detected` · `config-updated` · `history-cleared` · `all-data-cleared`

---

### 4.6 ScreenMonitoringService - 屏幕监测与录屏

**核心职责**：
- 以可配置帧率周期性采集屏幕帧
- 每帧触发 OCR 识别（击杀/死亡/助攻/金币/时间）· 对象检测 · 血条/蓝条读取
- 将识别出的 GameState 同步给评分引擎与 AppStore
- **录屏**：开始/停止录屏，自动保存录屏记录并持久化
- **录制历史**：支持在"录屏记录"页面查看与清空

**核心配置**：

```typescript
interface MonitoringConfig {
  enabled: boolean;
  mode: 'capture' | 'analysis' | 'recording';
  fps: number;
  resolution: { width: number; height: number };
  quality: 'low' | 'medium' | 'high';
  enableRecording: boolean;
  recordingFormat: 'mp4' | 'webm' | 'gif';
  maxRecordingDuration: number;
  enableOCR: boolean;
  enableObjectDetection: boolean;
  enableHealthBarDetection: boolean;
  enableScorePanelDetection: boolean;
  autoSave: boolean;
  savePath: string;
  captureIntervalMs: number;
}
```

**对外事件**：`started` · `stopped` · `paused` · `resumed` · `frame-captured` · `analysis-complete` · `recording-started` · `recording-stopped` · `all-data-cleared` · `analysis-history-cleared` · `recordings-cleared`

---

### 4.7 AppStore - 状态管理与服务协调

**统一状态**（[AppStore.ts](file:///c:/Users/Administrator/Desktop/LookGm/src/store/AppStore.ts)）：

```typescript
interface AppState {
  page: 'login' | 'home' | 'profile' | 'ai-settings' | 'settings' | 'recording-history';
  user: UserProfile | null;
  gameId: GameId | null;
  role: Role | null;
  score: ScoreResult | null;
  captureRunning: boolean;
  recording: boolean;
  floatingWindowEnabled: boolean;
  voiceEnabled: boolean;
  monitoringEnabled: boolean;
  antiDetectionEnabled: boolean;
}
```

**职责**：
1. **页面路由**：`navigate(page)` 统一跳转，未登录自动回到 login
2. **登录会话**：封装 `loginWithWechat` / `loginWithQQ` / `loginAsGuest` / `logout`
3. **游戏切换**：`selectGame(gameId)` / `selectRole(role)` / `computeScoreNow()`
4. **采集/录屏开关**：`startCapture()` / `stopCapture()` + 同步触发悬浮窗 / 监测服务
5. **偏好开关**：`setVoiceEnabled` / `setFloatingWindowEnabled` / `setMonitoringEnabled` / `setAntiDetectionEnabled`
6. **AI 桥接**：`configureAIProvider` / `setCurrentAIProvider` / `chatWithAI` / `testAIProvider`
7. **数据清理**：`clearAllLocalData()` / `getStorageSummary()` 提供个人中心清理入口

**事件协作**：
- AppStore 在构造函数内订阅各服务的事件，将其转化为统一的 `state-changed` + UI 具体事件
- 所有页面订阅 AppStore 以获得最小必要状态更新

---

## 五、UI 页面数据流

### 5.1 LoginPage 登录页

```
用户点击 [微信授权登录]
  → AppStore.loginWithWechat()
      → AuthService.loginWithWechat()
          → 写入 lookgm_currentUser + lookgm_sessionToken
          → emit('login', user)
      → AppStore 收到 login 事件
          → currentUser = user, page = 'home'
          → emit('state-changed')
  → App.tsx 渲染 HomePage
```

### 5.2 HomePage 主界面

```
选择游戏 → selectGame('king-of-glory')
选择分路 → selectRole('jungle')
启动屏幕采集 → startCapture()
  → ScreenMonitoringService.start()
  → FloatingWindowService.start()
立即评分 → computeScoreNow()
  → ScoringEngine.calculate(state)
  → 触发 'score-updated' 事件
  → VoiceCommunicationService.speakScore()
  → FloatingWindowService.updateScoreDisplay()
```

### 5.3 AISettingsPage AI 配置页

```
编辑 Provider 配置（API Key / URL / Model / Token / Enabled）
  → AppStore.configureAIProvider(provider, {...})
      → AIApiService.configureProvider(...)
      → 保存到 lookgm_ai_configs

点击 "选用"
  → AppStore.setCurrentAIProvider(provider)
  → 自动切换当前 AI Provider

点击 "测试连接"
  → AppStore.testAIProvider(provider)
  → 返回 { success, responseTime, error }

发送对话测试
  → AppStore.chatWithAI(message)
  → 按对应 Provider 的 request 协议调用
  → 页面展示 AI 回复
```

### 5.4 ProfilePage 个人中心

- **用户信息卡片**：头像 / 昵称（可点击修改） / 登录方式 / 登录时间 / 等级 / 对局次数
- **快捷入口**：AI 配置 / 偏好设置 / 录屏记录 / 开始对局
- **存储信息**：列出本地存储项数量 · 估算占用
- **清理本地数据**：调用 `clearAllLocalData()` 清理 AI / 悬浮窗 / 语音 / 录屏配置，保留登录会话
- **AI 状态**：展示当前 Provider + 是否已配置 API Key + 跳转到 AI 配置
- **退出登录**：二次确认 → `logout()` → 回到 login 页

---

## 六、防检测设计

### 为什么不会被游戏反作弊系统检测

| 检测点 | 设计方案 |
|--------|---------|
| 进程注入 | ❌ 绝对不注入游戏进程，仅使用系统 API 读取屏幕画面 |
| 内存读取 | ❌ 不读取游戏客户端内存，仅做视觉识别 |
| 游戏 SDK | ❌ 不调用任何游戏厂商 SDK |
| UI 修改 | ❌ 不修改游戏画面，悬浮窗为系统级 TYPE_APPLICATION_OVERLAY |
| 数据上传 | ❌ 所有识别/评分/语音都在本地处理；AI API 仅由用户手动触发 |
| 时间模式 | ✅ 采集间隔随机化（±25% 抖动），避免固定模式识别 |

### 技术要点

1. 屏幕采集仅在用户授权后进行
2. AI 识别全部使用设备端能力（TFLite / 本地 OCR）
3. 语音播报使用系统级 TTS，不 hook 游戏音频
4. 悬浮窗采用系统 TYPE_APPLICATION_OVERLAY
5. 用户可以在首页手动开关防检测模式

---

## 七、扩展新游戏接入指南

1. 新建目录 `src/game-adapters/<game-id>/`
2. 实现 `GameAdapter` 抽象基类：

```typescript
class XxxGameAdapter extends GameAdapter {
  constructor() {
    super({
      id: '<game-id>',
      name: '<Display Name>',
      packageName: '<Android Package>',
      supportedPlatforms: ['android', 'ios'],
      maxScore: 16.0,
      roles: ['exp_lane', 'jungle', 'mid_lane', 'roam', 'gold_lane'],
    });
  }

  protected initRegions() { /* 游戏 UI 识别区域 */ }
  protected initScoringRules() { /* 评分规则 */ }
  protected initTasks() { /* 加分任务 */ }
}
```

3. 在 `AppStore.getAvailableGames()` 中追加游戏信息
4. 在首页会以游戏卡片形式自动出现

---

## 八、安全与隐私声明

- ✅ 仅供学习研究用途
- ✅ 合规使用，遵守各游戏的用户协议
- ❌ 不提供任何作弊相关功能
- ❌ 不修改、不注入、不读取游戏进程

---

## 九、后续规划

- [ ] 完成 Android 原生模块（MediaProjection / 悬浮窗 / TTS）
- [ ] 完成 iOS 原生模块（ScreenCaptureKit / 系统悬浮窗 / AVSpeechSynthesizer）
- [ ] 集成 TFLite 模型推理框架 + 中文 OCR
- [ ] 游戏区域识别训练（血条 / 金币栏 / 击杀面板 / 游戏时间）
- [ ] 战绩历史记录与统计图表
- [ ] 用户中心云端同步（可选/默认关闭）
- [ ] AI 模型下拉列表动态更新

---

**版本**：v1.2.0
**最后更新**：2025
