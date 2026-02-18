# QNVR

QNVR是一个基于Android平台的网络视频监控应用，支持通过RTSP协议和Web界面进行视频流预览与配置管理。
下载体验: 
- [飞书文档](https://pipiqiang.feishu.cn/wiki/DgrhwZPmAiEi2hkqO0WcesZXnsh)

## 功能特点

- 支持RTSP协议视频流输出，可用于远程监控
- 提供Web界面进行参数配置（分辨率、码率、编码格式等）
- 支持视频水印（时间、设备名称）
- 支持多种编码格式（H.264/AVC、H.265/HEVC、VP8、VP9）
- 可选择硬件或软件编码方式

## 环境要求

- Android设备：Android 7.0（API 24）及以上
- 开发环境：Android Studio 2022.3+，Kotlin 1.9+，Gradle 8.5+

## 快速开始

### 环境配置

构建前请确保已安装以下环境：
- JDK 17
- Android SDK（API 34）
- Android Studio 2022.3+（推荐）或命令行工具

### 编译运行

#### 使用 Android Studio

1. 克隆项目到本地
   ```bash
   git clone https://github.com/ycq3/qnvr.git
   cd qnvr
   ```

2. 用Android Studio打开项目

3. 等待 Gradle 同步完成

4. 连接Android设备或启动模拟器

5. 点击运行按钮或使用快捷键 Shift+F10 运行应用

#### 使用命令行

1. 克隆项目到本地
   ```bash
   git clone https://github.com/ycq3/qnvr.git
   cd qnvr
   ```

2. 授予 Gradle Wrapper 执行权限
   ```bash
   chmod +x gradlew
   ```

3. 编译 Debug 版本
   ```bash
   ./gradlew assembleDebug
   ```

4. 安装到已连接的设备
   ```bash
   ./gradlew installDebug
   ```

5. 编译 Release 版本
   ```bash
   ./gradlew assembleRelease
   ```

   **注意**：编译 Release 版本需要配置签名信息，详见下方 [签名配置](#签名配置)。

#### 常用 Gradle 命令

```bash
# 清理构建产物
./gradlew clean

# 编译并运行所有测试
./gradlew test

# 编译 Debug 版本并安装
./gradlew installDebug

# 查看所有可用任务
./gradlew tasks
```

### 签名配置

如需编译 Release 版本，需配置签名信息。有以下两种方式：

#### 方式一：使用 local.properties

在项目根目录创建 `local.properties` 文件，添加以下内容：

```properties
sdk.dir=/path/to/android/sdk
qnvrStoreFile=/path/to/keystore.jks
qnvrStorePassword=your_store_password
qnvrKeyAlias=your_key_alias
qnvrKeyPassword=your_key_password
```

#### 方式二：使用环境变量

设置以下环境变量：

```bash
export QNVR_STORE_PASSWORD=your_store_password
export QNVR_KEY_PASSWORD=your_key_password
```

同时在 `local.properties` 或通过 Gradle 属性配置密钥库文件路径和别名。

### 使用方法

1. 首次打开应用时，授予必要权限（相机、网络、唤醒锁等）
2. 应用启动后，主界面会显示RTSP地址和Web界面地址
3. 通过RTSP地址可使用支持RTSP的播放器（如VLC）查看视频流
4. 通过Web界面可访问配置页面，调整各项参数

## 配置说明

Web配置界面提供以下可调整参数：

- 设备名称及显示设置
- RTSP服务用户名/密码
- RTSP端口（默认18554）
- 视频码率（默认4000000bps）
- 帧率（1-30fps）
- 编码格式（H.264/AVC、H.265/HEVC等）
- 编码器选择（自动/特定硬件/软件编码器）
- 分辨率（1280x720、1920x1080、640x480）
- 手电筒控制
- 时间水印开关
- 变焦控制

## 技术架构

- 视频采集：基于Android Camera2 API
- 视频编码：使用Android MediaCodec
- RTSP服务：自定义实现的RTSP服务器
- Web服务：基于NanoHTTPD
- 配置管理：SharedPreferences存储配置参数

## 常见问题

1. **Q: 高码率设置下仍出现马赛克怎么办？**
   A: 尝试切换至H.265编码（需设备支持），或调整帧率与I帧间隔，也可检查网络传输是否存在丢包。

2. **Q: 无法访问Web界面？**
   A: 确保设备与访问设备在同一局域网，检查防火墙设置，确认应用已获得网络权限。

3. **Q: 视频流延迟较高？**
   A: 可降低分辨率或码率，选择硬件编码，或优化网络环境。

