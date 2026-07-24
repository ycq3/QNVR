# QNVR (AI看家) 接口文档

> 版本: 1.1.0  
> 最后更新: 2026-07-24
>
> 本文档适用于 AI看家 (QNVR) Android 应用 v1.1.0 及以上版本。

本文档描述了 QNVR Android 应用对外提供的 HTTP REST API 和 RTSP 流媒体接口，方便第三方程序、智能家居平台或监控系统进行集成。

---

## 目录

1. [基础信息](#1-基础信息)
2. [HTTP REST API](#2-http-rest-api)
   - 2.1 [获取运行状态](#21-获取运行状态-get-apistatus)
   - 2.2 [获取详细统计](#22-获取详细统计-get-apistats)
   - 2.3 [获取配置](#23-获取配置-get-apiconfig)
   - 2.4 [修改配置](#24-修改配置-post-apiconfig)
   - 2.5 [获取编码器列表](#25-获取编码器列表-get-apiencoders)
   - 2.6 [获取日志](#26-获取日志-get-apilogs)
   - 2.7 [重启服务](#27-重启服务-post-apirestart)
   - 2.8 [MJPEG 实时预览](#28-mjpeg-实时预览-get-streammjpg)
3. [RTSP 流媒体协议](#3-rtsp-流媒体协议)
4. [配置字段参考](#4-配置字段参考)
5. [错误处理](#5-错误处理)
6. [集成示例](#6-集成示例)

---

## 1. 基础信息

- **默认 Web 端口**: `8080`
- **默认 RTSP 端口**: `18554`
- **基础 URL**: `http://<设备IP>:8080/`
- **RTSP URL**: `rtsp://<用户名>:<密码>@<设备IP>:18554/live`
- **协议版本**: HTTP/1.1, RTSP/1.0
- **内容格式**: JSON (UTF-8)

### 认证说明

- Web 配置界面和 HTTP API **当前无独立认证**（建议在内网使用）。
- RTSP 流支持 **Basic 认证**，用户名和密码可在 Web 界面或 App 内设置。

---

## 2. HTTP REST API

### 2.1 获取运行状态 `GET /api/status`

返回当前服务运行状态、RTSP/Web 地址及编码器基本信息。

**请求示例:**
```http
GET /api/status HTTP/1.1
Host: 192.168.1.100:8080
```

**响应示例:**
```json
{
  "rtsp": "rtsp://admin:123456@192.168.1.100:18554/live",
  "web": "http://192.168.1.100:8080/",
  "deviceName": "MyCamera",
  "showDeviceName": false,
  "bitrate": 2000000,
  "width": 1920,
  "height": 1080,
  "fps": 20,
  "port": 18554,
  "currentFps": 20,
  "cpuUsage": 12.5,
  "encoderName": "c2.android.hevc.encoder",
  "encoderType": "video/hevc"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| rtsp | string | RTSP 播放地址（含认证信息） |
| web | string | Web 配置页面地址 |
| deviceName | string | 设备名称 |
| showDeviceName | boolean | 是否显示设备名水印 |
| bitrate | int | 当前视频码率（bps） |
| width | int | 视频宽度 |
| height | int | 视频高度 |
| fps | int | 目标帧率 |
| port | int | RTSP 服务端口 |
| currentFps | int | 实际输出帧率 |
| cpuUsage | float | 当前 CPU 占用百分比 |
| encoderName | string | 当前使用的编码器名称 |
| encoderType | string | 编码格式 MIME 类型 |

---

### 2.2 获取详细统计 `GET /api/stats`

返回系统监控和性能统计数据。

**请求示例:**
```http
GET /api/stats HTTP/1.1
Host: 192.168.1.100:8080
```

**响应示例:**
```json
{
  "currentFps": 20,
  "cpuUsage": 12.5,
  "networkRxBytes": 1024000,
  "networkTxBytes": 5120000,
  "networkRxKbps": 102.4,
  "networkTxKbps": 512.0,
  "encoderName": "c2.android.hevc.encoder",
  "encoderType": "video/hevc",
  "width": 1920,
  "height": 1080,
  "bitrate": 2000000,
  "cpuTemp": 42.5,
  "batteryTemp": 35.0,
  "batteryLevel": 85,
  "lowPowerMode": true,
  "watermarkPosition": "bottom",
  "watermarkOpacity": 180
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| currentFps | int | 当前实际帧率 |
| cpuUsage | float | CPU 使用率 (%) |
| networkRxBytes | long | 累计接收字节数 |
| networkTxBytes | long | 累计发送字节数 |
| networkRxKbps | float | 实时接收速率 (Kbps) |
| networkTxKbps | float | 实时发送速率 (Kbps) |
| encoderName | string | 编码器名称 |
| encoderType | string | 编码格式 |
| width | int | 分辨率宽 |
| height | int | 分辨率高 |
| bitrate | int | 当前码率 |
| cpuTemp | float | CPU 温度 (°C) |
| batteryTemp | float | 电池温度 (°C) |
| batteryLevel | int | 电池电量 (%) |
| lowPowerMode | boolean | 低功耗模式开关状态 |
| watermarkPosition | string | 水印位置 (top/bottom) |
| watermarkOpacity | int | 水印透明度 (0-255) |

---

### 2.3 获取配置 `GET /api/config`

返回当前所有配置参数，可用于初始化第三方客户端界面。

**请求示例:**
```http
GET /api/config HTTP/1.1
Host: 192.168.1.100:8080
```

**响应示例:**
```json
{
  "username": "admin",
  "password": "123456",
  "port": 18554,
  "bitrate": 2000000,
  "width": 1920,
  "height": 1080,
  "deviceName": "MyCamera",
  "showDeviceName": false,
  "encoderName": "c2.android.hevc.encoder",
  "mimeType": "video/hevc",
  "fps": 20,
  "pushEnabled": false,
  "pushUrl": "",
  "pushUseRemoteConfig": false,
  "pushConfigUrl": "",
  "audioEnabled": true,
  "iFrameInterval": 1,
  "bitrateMode": "cq",
  "noiseReduction": "high_quality",
  "videoStabilization": true,
  "edgeEnhancement": true,
  "exposureCompensation": 0.0,
  "watermarkPosition": "bottom",
  "watermarkOpacity": 180,
  "lowPowerMode": true,
  "sampleRate": 44100,
  "rtsp": "rtsp://admin:123456@192.168.1.100:18554/live",
  "web": "http://192.168.1.100:8080/"
}
```

---

### 2.4 修改配置 `POST /api/config`

修改应用配置。请求体为 JSON 对象，只需包含需要修改的字段，未包含的字段保持不变。

**部分配置项变更会触发服务重启（如分辨率、码率、编码器等），请谨慎调用。**

**请求头:**
```
Content-Type: application/json
```

**请求示例:**
```http
POST /api/config HTTP/1.1
Host: 192.168.1.100:8080
Content-Type: application/json
```json
{
  "bitrate": 1500000,
  "fps": 15,
  "watermarkPosition": "top",
  "watermarkOpacity": 200,
  "noiseReduction": "fast",
  "lowPowerMode": true
}
```

**响应示例:**
```json
{}
```

#### 支持的配置字段

| 字段 | 类型 | 范围/可选值 | 说明 |
|------|------|-------------|------|
| torch | boolean | true/false | 手电筒开关 |
| watermark | boolean | true/false | 时间水印总开关（与RTSP水印同步） |
| zoom | float | 1.0 ~ maxZoom | 数码变焦倍数 |
| deviceName | string | - | 设备名称（用于水印显示） |
| showDeviceName | boolean | true/false | 是否显示设备名水印 |
| username | string | - | RTSP/Web 用户名 |
| password | string | - | RTSP/Web 密码 |
| port | int | 1024-65535 | RTSP 服务端口 |
| bitrate | int | >= 100000 | 视频码率 (bps) |
| fps | int | 1-60 | 视频帧率 |
| encoderName | string | ""或具体编码器名 | ""表示自动选择 |
| mimeType | string | "video/avc", "video/hevc" | 编码格式 |
| width | int | - | 视频分辨率宽 |
| height | int | - | 视频分辨率高 |
| pushEnabled | boolean | true/false | 主动推流开关 |
| pushUrl | string | RTSP URL | 推流目标地址 |
| pushUseRemoteConfig | boolean | true/false | 是否使用远程推流配置 |
| pushConfigUrl | string | HTTP URL | 远程推流配置地址 |
| audioEnabled | boolean | true/false | 音频采集开关 |
| iFrameInterval | int | >= 1 | I帧间隔（秒） |
| bitrateMode | string | "cq", "VBR", "CBR" | 码率控制模式（注意大小写：cq / VBR / CBR） |
| noiseReduction | string | "off", "fast", "high_quality" | 降噪等级 |
| videoStabilization | boolean | true/false | 视频防抖开关 |
| edgeEnhancement | boolean | true/false | 边缘增强开关 |
| exposureCompensation | float | -2.0 ~ +2.0 | 曝光补偿 |
| watermarkPosition | string | "top", "bottom" | 水印位置 |
| watermarkOpacity | int | 0-255 | 水印透明度（0=完全透明，255=不透明）。Web 界面旧版字段 `watermarkAlpha` (0.0-1.0) 也会被接受并自动转换 |
| lowPowerMode | boolean | true/false | 无客户端时自动降帧率 |
| sampleRate | int | 8000/11025/16000/22050/44100/48000 | 音频采样率 |
| autoStart | boolean | true/false | 启动应用后自动启动录像服务 |
| bootAutoStart | boolean | true/false | 开机自启（后台保活） |

---

### 2.5 获取编码器列表 `GET /api/encoders`

返回当前设备支持的所有视频编码器信息，用于前端下拉选择。

**响应示例:**
```json
[
  {
    "name": "c2.android.hevc.encoder",
    "mimeType": "video/hevc",
    "isHardwareAccelerated": true,
    "displayName": "H.265/HEVC (硬件) - c2.android.hevc.encoder"
  },
  {
    "name": "c2.android.avc.encoder",
    "mimeType": "video/avc",
    "isHardwareAccelerated": true,
    "displayName": "H.264/AVC (硬件) - c2.android.avc.encoder"
  }
]
```

---

### 2.6 获取日志 `GET /api/logs`

返回应用最近 200 条相关日志，便于远程调试。

**响应示例:**
```json
{
  "logs": [
    "07-24 10:00:01.234  I  CameraController: Created watermark bitmap 1920x80",
    "07-24 10:00:02.345  I  VideoEncoder: Starting video encoder with mimeType: video/hevc..."
  ]
}
```

**错误响应:**
```json
{
  "logs": [],
  "error": "Unable to run logcat"
}
```

---

### 2.7 重启服务 `POST /api/restart`

延迟 1 秒后发送服务重启广播，可用于远程恢复异常状态。

**响应示例:**
```json
{
  "status": "restarting"
}
```

---

### 2.8 MJPEG 实时预览 `GET /stream.mjpg`

返回 Motion-JPEG 实时视频流，可直接在 `<img>` 标签或支持 MJPEG 的播放器中使用。

**Content-Type:** `multipart/x-mixed-replace; boundary=frame`

**HTML 使用示例:**
```html
<img src="http://192.168.1.100:8080/stream.mjpg" alt="实时预览">
```

---

## 3. RTSP 流媒体协议

QNVR 内置 RTSP 服务器，支持通过 RTSP/RTP over TCP (Interleaved) 协议传输视频和音频。

### 连接信息

| 项目 | 默认值 | 说明 |
|------|--------|------|
| 协议 | RTSP/1.0 | - |
| 传输 | RTP/AVP/TCP | 仅支持 TCP 传输（Interleaved） |
| 视频编码 | H.264/AVC 或 H.265/HEVC | 可在配置中切换 |
| 音频编码 | AAC-LC | 采样率可配置 |
| 视频轨道 | trackID=0 | - |
| 音频轨道 | trackID=1 | 仅在音频启用时可用 |

### 标准 RTSP 流程

```
1. OPTIONS      -> 查询支持的方法
2. DESCRIBE     -> 获取 SDP 描述信息
3. SETUP        -> 建立 RTP over TCP 通道
4. PLAY         -> 开始传输媒体流
5. TEARDOWN     -> 结束会话
```

### VLC 播放示例

```bash
vlc rtsp://admin:123456@192.168.1.100:18554/live
```

### FFmpeg 拉流/转码示例

```bash
# 直接播放
ffplay -rtsp_transport tcp rtsp://admin:123456@192.168.1.100:18554/live

# 录制为 MP4
ffmpeg -rtsp_transport tcp -i rtsp://admin:123456@192.168.1.100:18554/live -c copy output.mp4

# 转码为低码率流推送至其他平台
ffmpeg -rtsp_transport tcp -i rtsp://admin:123456@192.168.1.100:18554/live \
  -c:v libx264 -preset fast -b:v 1000k -c:a aac -b:a 64k \
  -f flv rtmp://live.example.com/stream/key
```

---

## 4. 配置字段参考

以下字段可通过 `GET/POST /api/config` 读写：

### 视频参数

| 字段 | 默认值 | 说明 |
|------|--------|------|
| width | 1920 | 采集分辨率宽度 |
| height | 1080 | 采集分辨率高度 |
| fps | 20 | 目标帧率 |
| bitrate | 2000000 | 视频码率 (bps)，HEVC 默认 2Mbps，H.264 默认 4Mbps |
| mimeType | "video/hevc" | 编码格式，推荐 H.265 以节省带宽 |
| encoderName | null | 指定编码器，null/空字符串表示自动选择 |
| iFrameInterval | 1 | 关键帧间隔（秒），越小恢复花屏越快 |
| bitrateMode | "cq" | 码率控制：cq（恒定质量）、vbr（可变码率）、cbr（恒定码率） |

### 图像增强

| 字段 | 默认值 | 说明 |
|------|--------|------|
| noiseReduction | "high_quality" | 降噪：off / fast / high_quality |
| videoStabilization | true | 视频防抖（含 OIS 光学防抖，如设备支持） |
| edgeEnhancement | true | 边缘增强 |
| exposureCompensation | 0.0 | 曝光补偿 (-2.0 ~ +2.0) |

### 水印与覆盖

| 字段 | 默认值 | 说明 |
|------|--------|------|
| watermark | true | 时间水印开关 |
| showDeviceName | false | 设备名水印开关 |
| deviceName | 设备型号 | 水印显示的设备名称 |
| watermarkPosition | "bottom" | 水印位置：top / bottom |
| watermarkOpacity | 180 | 水印透明度 (0-255)，255 为完全不透明 |

### 网络与推流

| 字段 | 默认值 | 说明 |
|------|--------|------|
| port | 18554 | RTSP 监听端口 |
| username | "admin" | RTSP/Web 认证用户名 |
| password | "" | RTSP/Web 认证密码 |
| pushEnabled | false | 主动推流到第三方 RTSP 服务器 |
| pushUrl | "" | 推流目标 RTSP 地址 |
| pushUseRemoteConfig | false | 从远程 URL 读取推流配置 |
| pushConfigUrl | "" | 远程推流配置文件地址 |

### 音频参数

| 字段 | 默认值 | 说明 |
|------|--------|------|
| audioEnabled | true | 音频采集开关 |
| sampleRate | 44100 | 音频采样率 (Hz) |

### 系统参数

| 字段 | 默认值 | 说明 |
|------|--------|------|
| torch | false | 手电筒开关 |
| zoom | 1.0 | 数码变焦倍数 |
| autoStart | true | 启动应用后自动启动录像服务 |
| bootAutoStart | true | 开机自启（后台保活） |
| lowPowerMode | true | 无客户端时自动降低帧率至 5fps 并降低码率，减少电量占用 |

---

## 5. 错误处理

### HTTP API 错误码

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 OK | 请求成功 |
| 404 Not Found | 接口路径不存在 |
| 500 Internal Server Error | 服务端内部错误 |

### RTSP 错误码

| RTSP 状态码 | 说明 |
|-------------|------|
| 200 OK | 成功 |
| 401 Unauthorized | Basic 认证失败 |
| 405 Method Not Allowed | 不支持的 RTSP 方法 |
| 461 Unsupported Transport | 仅支持 RTP/AVP/TCP |
| 500 Internal Server Error | 编码器未就绪或其他内部错误 |

---

## 6. 集成示例

### Python 获取状态并修改配置

```python
import requests

BASE_URL = "http://192.168.1.100:8080"

# 获取当前状态
status = requests.get(f"{BASE_URL}/api/status").json()
print(f"当前编码器: {status['encoderName']}, 帧率: {status['currentFps']} FPS")

# 降低码率以节省带宽
requests.post(f"{BASE_URL}/api/config", json={
    "bitrate": 1000000,
    "fps": 15,
    "lowPowerMode": True
})
```

### Home Assistant 集成 (Generic Camera)

```yaml
camera:
  - platform: generic
    name: AI看家摄像头
    still_image_url: "http://192.168.1.100:8080/stream.mjpg"
    stream_source: "rtsp://admin:123456@192.168.1.100:18554/live"
```

### Node-RED 自动化示例

使用 `http request` 节点调用 `POST /api/config` 实现根据时间段自动切换码率：

```json
{
  "method": "POST",
  "url": "http://192.168.1.100:8080/api/config",
  "payload": {
    "bitrate": 4000000,
    "fps": 30
  }
}
```

---

## 附录：版本历史

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| 1.0.0 | 2024-XX-XX | 初始版本，支持基础 RTSP 和 Web 配置 |
| 1.1.0 | 2026-07-24 | 新增水印位置/透明度、低功耗模式、图像增强参数、日志/重启 API、自适应码率、网络优化 |
