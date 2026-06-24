# GameAI Assistant - 全游戏通用AI视觉助手

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/Architecture-MVVM-blue" alt="Architecture">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

基于纯视觉合规方案的**全游戏通用AI辅助工具**，不读取游戏内存，通过屏幕截图+AI视觉分析提供实时战术指导。默认适配**王者荣耀**，可扩展至任意手游。

## ✨ 核心特性

| 模块 | 说明 |
|------|------|
| 🎯 **实时评分** | 五分路四段位（顶级/金牌/银牌/铜牌），KDA+经济+时间三维评分 |
| 🤖 **AI战术分析** | 事件驱动（击杀/团战/开局），支持 9 种模型供应商 |
| 🎤 **语音对话** | 豆包模式：边说边看，STT→截图→AI→TTS 全自动 |
| 🪟 **悬浮球助手** | 边缘吸附 + 分路任务指引 + AI 分析 + 语音对话气泡 |
| 🔄 **自动更新** | GitHub Releases 检测 + 后台下载 + 一键安装 |
| 🖥️ **PC 端服务** | FastAPI + WebSocket，自动检测 Ollama/vLLM/LM Studio |
| 🌐 **双模型架构** | 内网本地模型 + 全网云端 API，对话/分析可分别指定模型 |
| 🔒 **纯视觉合规** | 不读内存、不注入、不封号，MediaProjection 录屏 + ML Kit OCR |

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐│
│  │Dashboard │  │ Models   │  │ History  │  │ Profile  ││
│  │Fragment  │  │Fragment  │  │Fragment  │  │Fragment  ││
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘│
│       └──────────────┴──────────────┴──────────────┘     │
│                          │                                │
│                   MainViewModel (MVVM)                    │
│                          │                                │
│  ┌───────────────────────┼───────────────────────────┐   │
│  │ Engine Layer          │                           │   │
│  │ ├─ GameStateManager   │  ├─ CloudAiClient         │   │
│  │ ├─ ScreenAnalysisEngine│  ├─ VoiceConversationEng. │   │
│  │ ├─ GameTextRecognizer │  ├─ ScreenCaptureService  │   │
│  │ └─ ScoringEngine      │  └─ FloatingWindowService │   │
│  └───────────────────────┴───────────────────────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│                 PC Server (Python FastAPI)               │
│  ├─ ws_server.py   (WebSocket 实时通信)                  │
│  ├─ model_manager.py (自动检测 Ollama/vLLM/LM Studio)    │
│  ├─ scoring_engine.py (本地评分引擎)                     │
│  └─ start.bat (一键启动)                                 │
└─────────────────────────────────────────────────────────┘
```

## 🚀 快速开始

### Android 端

1. Android Studio 打开 `GameAIAssistant/` 目录
2. 同步 Gradle 依赖
3. 运行到 Android 10+ 设备
4. 在「模型配置」页绑定 AI 供应商和 API Key

### PC 端服务（可选，用于本地模型）

```bash
cd GameAIAssistant/pcserver
start.bat
# 或手动：python main.py --host 0.0.0.0 --port 8765
```

### 版本号规则

版本号格式：`YYYYMMDD`（正式版）/ `YYYYMMDD-betaN`（Beta版）

## 📂 项目结构

```
GameAIAssistant/
├── app/
│   └── src/main/
│       ├── java/com/gameai/
│       │   ├── ai/           # AI 引擎（客户端/分析/语音）
│       │   ├── db/           # Room 数据库
│       │   ├── engine/       # 游戏状态管理
│       │   ├── model/        # 数据模型
│       │   ├── recognition/  # OCR 文字识别
│       │   ├── service/      # 悬浮窗/录屏/语音服务
│       │   ├── ui/           # Fragment/Activity/Widget
│       │   └── utils/        # 工具类
│       └── res/              # 资源文件
└── pcserver/                 # PC 端服务
    ├── main.py               # FastAPI 入口
    ├── ws_server.py          # WebSocket 服务
    ├── model_manager.py      # 本地模型管理
    ├── scoring_engine.py     # 本地评分引擎
    └── start.bat             # 一键启动脚本
```

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License — 详见 [LICENSE](LICENSE)
