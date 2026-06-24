# GameAI Assistant - 全游戏通用AI视觉助手

## 项目简介
基于手机屏幕流采集 + PC端AI分析的实时游戏辅助系统。

## 功能特性

### 已实现的核心功能
- 📱 **Android端**: 完整功能APK
  - MediaProjection屏幕实时采集 (720p/10fps可调)
  - 帧差分检测 & JPEG压缩传输
  - WebSocket实时通信 (心跳保活/断线重连)
  - 悬浮窗实时显示评分 (支持拖拽/点击播报)
  - TTS语音播报评分
  - 本地评分引擎 (8维度90+规则)
  - 对局状态机 (大厅→匹配→选人→加载→对局→结算)
  - Room数据库历史对局记录
  - 完整设置界面 (服务器/游戏/模型/参数配置)
  - MMKV高性能KV存储

- 🖥️ **PC端**: Python FastAPI服务
  - WebSocket多连接管理
  - 实时帧接收与分析
  - HTTP健康检查API
  - 设备管理接口

### 评分体系 (8维度共100分)
| 维度 | 满分 | 评价标准 |
|------|------|----------|
| KDA | 25分 | 击杀/死亡/助攻综合评估 |
| 经济 | 20分 | 每分钟金币/补刀数 |
| 参团率 | 15分 | 团队战斗参与度 |
| 视野 | 15分 | 视野得分/守卫布置 |
| 输出 | 10分 | 伤害输出量 |
| 生存 | 5分 | 死亡次数评估 |
| 发育 | 5分 | 补刀数/线上发育 |
| 节奏 | 5分 | 推塔/控龙/峡谷先锋 |

### 评分等级
- **S级** (90-100分): 完美表现
- **A级** (80-89分): 优秀
- **B级** (65-79分): 良好
- **C级** (50-64分): 一般
- **D级** (0-49分): 需改进

## 项目结构

```
GameAIAssistant/
├── app/                    # 主应用模块
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/gameai/
│       │   ├── GameAIApplication.kt
│       │   ├── ui/
│       │   │   ├── MainActivity.kt
│       │   │   ├── MatchHistoryAdapter.kt
│       │   │   └── settings/SettingsActivity.kt
│       │   ├── service/
│       │   │   ├── ScreenCaptureService.kt
│       │   │   ├── WebSocketService.kt
│       │   │   ├── FloatingWindowService.kt
│       │   │   └── VoiceService.kt
│       │   ├── recognition/
│       │   │   └── GameStateDetector.kt
│       │   ├── engine/
│       │   │   ├── ScoringEngine.kt
│       │   │   └── MatchStateMachine.kt
│       │   ├── model/
│       │   │   ├── WebSocketMessage.kt
│       │   │   ├── ScoreResult.kt
│       │   │   ├── MatchData.kt
│       │   │   └── GameConfig.kt
│       │   ├── db/
│       │   │   ├── AppDatabase.kt
│       │   │   ├── MatchDao.kt
│       │   │   └── MatchEntity.kt
│       │   ├── viewmodel/MainViewModel.kt
│       │   └── utils/
│       │       ├── PreferencesManager.kt
│       │       └── FrameCompressor.kt
│       └── res/
│           ├── layout/ (5个布局文件)
│           ├── drawable/ (8个资源文件)
│           ├── values/ (3个值文件)
│           └── xml/ (2个备份规则)
├── common/                 # 公共模块
├── screenstream/           # 屏幕流模块
├── aiengine/               # AI引擎模块
├── pcserver/               # PC端Python服务
│   ├── main.py
│   └── requirements.txt
├── build.gradle
├── settings.gradle
├── gradlew.bat
└── build_apk.bat
```

## 构建要求

### Android端
- JDK 17+
- Android SDK (API 34)
- Gradle 8.5

### PC端
- Python 3.9+
- pip install -r pcserver/requirements.txt

## 使用方法

### 1. 启动PC服务端
```bash
cd pcserver
pip install -r requirements.txt
python main.py
```

### 2. 安装并打开手机APP
- 安装编译好的APK
- 授予悬浮窗权限
- 进入设置配置PC端IP地址

### 3. 开始使用
- 打开游戏
- 点击"开始辅助"
- 授予录屏权限
- 悬浮窗显示实时评分

## 技术栈
- **语言**: Kotlin (Android), Python (PC Server)
- **架构**: MVVM + LiveData + Room
- **通信**: WebSocket (OkHttp)
- **存储**: Room + MMKV
- **UI**: ViewBinding + Material Design
- **网络**: OkHttp + Retrofit
- **协程**: Kotlin Coroutines
- **图片**: Coil
