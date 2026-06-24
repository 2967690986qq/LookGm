# 构建指南

## 环境要求

### Android端
- Android Studio Hedgehog 2023.1.1+
- JDK 17
- Android SDK 34
- Gradle 8.2+

### PC服务端
- Python 3.10+
- pip 23.0+

## 构建步骤

### 1. 构建Android APK

#### 方法一：使用Android Studio
1. 打开Android Studio
2. 选择 "Open an Existing Project"
3. 选择 `GameAIAssistant` 目录
4. 等待Gradle同步完成
5. 连接Android设备（开启USB调试）
6. 点击 "Run" 按钮

#### 方法二：命令行构建
```bash
cd GameAIAssistant
./gradlew assembleDebug  # 构建调试版
# 或
./gradlew assembleRelease  # 构建发布版（需要签名）
```

构建完成后，APK文件位于：
- 调试版：`app/build/outputs/apk/debug/app-debug.apk`
- 发布版：`app/build/outputs/apk/release/app-release.apk`

### 2. 安装APK到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 启动PC服务端

```bash
cd pcserver
pip install -r requirements.txt
python main.py --host 0.0.0.0 --port 8765
```

## 配置说明

### 手机端配置
1. 首次启动需要授予权限：
   - 悬浮窗权限
   - 屏幕录制权限
   - 麦克风权限（语音功能）
   - 存储权限

2. 在APP设置中配置PC端IP地址：
   - 打开APP → 设置 → PC端配置
   - 输入PC端IP地址（如：192.168.1.100）
   - 端口默认为8765

### PC端配置
1. 确保防火墙允许端口8765入站
2. 获取本机IP地址：
   ```bash
   # Windows
   ipconfig
   
   # Linux/Mac
   ifconfig
   ```

3. 启动服务后，访问 `http://localhost:8765/health` 验证服务状态

## 故障排除

### 问题1：Gradle同步失败
- 检查网络连接
- 检查 `gradle-wrapper.properties` 中的Gradle版本是否正确
- 尝试清除Gradle缓存：`rm -rf ~/.gradle/caches/`

### 问题2：APK安装失败
- 确保手机已开启"未知来源"安装权限
- 检查手机Android版本是否 >= 7.0
- 尝试卸载旧版本后重新安装

### 问题3：WebSocket连接失败
- 确保手机和PC在同一局域网
- 检查PC端防火墙设置
- 尝试ping PC端IP地址
- 检查PC服务端是否正常运行

### 问题4：屏幕采集失败
- 确保已授予屏幕录制权限
- 重启APP
- 检查手机系统是否限制了后台服务

## 开发调试

### 查看日志
```bash
adb logcat | grep GameAI
```

### 调试WebSocket通信
使用WebSocket客户端工具（如Postman）连接到：
```
ws://PC端IP:8765/ws/game_stream?device_id=test&game=王者荣耀
```

## 打包发布版

1. 创建签名密钥：
```bash
keytool -genkey -v -keystore gameai-key.keystore -alias gameai -keyalg RSA -keysize 2048 -validity 10000
```

2. 配置签名信息在 `app/build.gradle`：
```groovy
android {
    signingConfigs {
        release {
            storeFile file('gameai-key.keystore')
            storePassword 'your_password'
            keyAlias 'gameai'
            keyPassword 'your_password'
        }
    }
    
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

3. 构建发布版：
```bash
./gradlew assembleRelease
```
