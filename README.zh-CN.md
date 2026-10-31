# DSH Mobile Gateway

[English](README.md) | **简体中文**

> 用手机或平板访问**电脑上正在运行的 DeepSeek Harness** —— 同一个实例、同一份会话、同一个正在跑的任务。

DSH 是 Electron 桌面程序，没有官方移动端。这个项目补上这一段：

```
手机 --HTTPS--> Cloudflare 边缘 <--QUIC 出站-- cloudflared --> 127.0.0.1:19399（网关）
      --> 127.0.0.1:19387（电脑上正在运行的 DSH 实例）--> 你的模型渠道
```

手机和电脑连的是**同一个进程**，所以会话列表、正在跑的任务、工作区文件天然一致。

---

## 仓库内容

| 目录 / 文件 | 说明 |
|---|---|
| `gateway/` | Node.js 网关：口令闸门、HTTP/WS 转发、窄屏布局注入、扫码登录码生成 |
| `qr.mjs` | 零依赖二维码**生成器**（纯 JS，约 14 KB） |
| `android/` | 安卓外壳应用：WebView 容器 + **纯 Java 二维码解码器** + Camera2 扫码 |
| `docs/` | 实现笔记与踩坑复盘 |

---

## 环境要求

- Windows 10/11（启动脚本是 `.cmd`）
- Node.js 18+
- `bin/cloudflared.exe` —— 从 [Cloudflare 官方](https://github.com/cloudflare/cloudflared/releases) 下载后改名放进去
- DSH 桌面端正在运行（网关是"接管"它，不是另起一个）

---

## 快速开始

### 1. 先开 DSH 桌面端

网关**接入**正在运行的实例，不会自己启动一个。

### 2. 启动网关

双击 `start.cmd`，等 20~40 秒，窗口里出现：

```
============================================================
  DSH 手机网关已就绪
============================================================
  手机上打开: https://xxxx-xxxx-xxxx.trycloudflare.com
  访问口令  : xxxxxxxxxx
============================================================
  已接入电脑上正在运行的 DSH（127.0.0.1:19387）
```

同样的内容写入 `gateway/access.txt`，可扫的二维码存到 `gateway/login-qr.png`。

**关掉黑窗口 = 立刻断开所有外部访问，桌面端照常运行。**

### 3. 手机连上来

**方式 A —— 浏览器。** 打开地址、输口令，可选"添加到主屏幕"。零安装。

**方式 B —— 安卓 APP。** 装 `DSH-mobile.apk` 后，扫网关显示的二维码，或直接粘贴地址。APP 会记住地址。

---

## 构建安卓 APP

不需要 Android Studio，也不需要 Gradle。前置：JDK 17 + Android SDK（build-tools 34.0.0、platform android-34）。

```powershell
# 一次性：生成你自己的签名密钥
keytool -genkeypair -v -keystore dsh-release.keystore -alias dsh `
        -keyalg RSA -keysize 2048 -validity 10000

# 构建
$env:JAVA_HOME        = 'C:\path\to\jdk-17'
$env:ANDROID_SDK_ROOT = 'C:\path\to\android-sdk'
$env:DSH_KS_PASS      = '你的 keystore 密码'

powershell -ExecutionPolicy Bypass -File android\build.ps1
```

构建链路：`aapt2 compile → aapt2 link → javac → d8 → 注入 dex → zipalign → apksigner`。

**升级必须沿用同一个签名密钥**，换了密钥用户只能卸载重装。

| 源文件 | 职责 |
|---|---|
| `MainActivity.java` | 配置页、WebView 容器、返回键、扫码入口 |
| `ScannerView.java` | Camera2 取流、逐像素旋转、预览渲染、解码调度 |
| `QrDecoder.java` | 纯 Java 二维码解码（寻像图形、格式信息、掩码、数据块、透视校正） |
| `BuildTag.java` | 界面上的版本水印 |

---

## 设计取舍

| 你可能会想 | 实际情况 |
|---|---|
| 直接让 DSH 监听局域网不就行了？ | 这一版 DSH 在启动时**显式拒绝** `0.0.0.0`（理由是会吧远程代码执行暴露到网络），绑具体 LAN IP 又过不了 config 校验。 |
| 为什么要走隧道而不是开端口？ | 网关**只绑 `127.0.0.1:19399`**，`cloudflared` 是**出站**连接。本机没开任何入站端口，`stop` 之后公网也进不来。 |
| 手机上为什么要整体缩小？ | DSH 的 Web 前端**没有任何响应式断点**（CSS 里只有一条 `prefers-reduced-motion`），实测布局最小需要约 500~520 px，390 px 竖屏会横向溢出。网关注入脚本把布局固定为 520 px，由浏览器等比缩放（约 0.75 倍）。 |
| 为什么不干脆另起一个 `dsh web` 给手机用？ | 试过。结果是**手机和电脑各聊各的**——两个独立进程，会话列表不共享。代理原实例才是真正"同一个"。 |
| 桌面端的鉴权怎么过？ | 桌面端用持久化的 secret（存在本机 credentials 里）签 cookie。网关读同一个 secret，按同样算法签一个 cookie，再把 `Host`/`Origin` 改写回 loopback。**不改桌面端进程，也不改它任何配置。** |
| 手机上会话列表在哪？ | 窄屏下 DSH 的会话抽屉默认收起。网关注入脚本在启动完成后自动展开一次。 |

---

## 安全边界（重要）

- **这个公网地址等于你电脑上的一台可执行命令的 agent。** 谁拿到"地址 + 口令"，谁就能在这个 profile 里读写文件、执行命令。
- 口令是 10 位随机字符，只走 HTTPS，并且**只在网关或 `cloudflared` 进程活着时有效**。
- **不用就关窗口。** 这是默认行为，也是唯一有效的日常防护。
- 隧道域名由 Cloudflare 随机分配、不易猜到，但**那不算安全边界**。真正的边界是口令。
- 流量在 Cloudflare 边缘终结 TLS（他们能看到明文）。如果你不接受，把 `startTunnel()` 换成你自己可控的隧道（如 Tailscale）。

---

## 已知限制

1. **每次启动地址都会变。** 快速隧道是临时的。想要固定地址需要 Cloudflare 账号 + 自己的域名做命名隧道，或换隧道方案。
2. **竖屏是整体缩放的**，不是原生移动布局。横屏才是完整布局。
3. 手机端没有右键、没有快捷键；拖拽面板、终端分屏的体验不如桌面。

---

## 已验证 / 未验证

**二维码生成器验证** —— 运行 `node qr-selftest.mjs`（覆盖 version 1–6 的结构不变量）：

- 符号尺寸与版本对应
- 三个定位图形齐全且正确
- 时序图形交替正确、暗模块存在

除结构自检外，生成器还与**独立实现**做过交叉验证：

- 与 Python `qrcode` 参考库**逐像素 0 差异**
- 由**独立解码器 pyzbar** 成功解出，6/6 样本通过

**真机验证：**

- 隧道建立、公网地址可达、口令闸门拦截与放行
- WebSocket（`/api/remote.mux`）穿越网关并保持连接
- 390×844 移动端仿真：布局完整，无横向溢出
- 手机真发一条消息并收到模型回复
- `stop.cmd` 收尾后无监听、无残留、桌面端不受影响
- **两台真机端到端**：一台 Android 16 手机、一台 Android 17 平板，横竖屏均通过，含扫码登录与 30fps 相机预览
- 解码器回归：2/2 张真机拍屏照片解出；低分辨率旧帧按预期失败

**未验证：**

- 长期稳定性（快速隧道偶有限流）
- Android 7~11（minSdk 写的是 24，但只在 Android 16/17 上实测过）
- 上报 `pixelStride == 2` 的老旧传感器（代码有兜底分支，未在真机跑过）

---

## 排错

| 现象 | 处理 |
|---|---|
| 报 `cloudflared.exe is missing` | 下载后放进 `bin\cloudflared.exe` |
| 报 `隧道 90 秒内未建立` | 出网被挡，确认能访问 Cloudflare，必要时给 cloudflared 配代理 |
| 手机打开空白或一直转圈 | 看 `logs/requests.log` 和 `logs/cloudflared.log` 末尾 |
| 口令对了还是回登录页 | 手机时钟/Cookie 异常；用 `地址/?k=口令` 一键链接 |
| 想换口令 | 停掉 → 删 `gateway/state.json` → 重启 |
| 端口被占用 | 网关启动时会清理 19399/19388 上的残留进程 |

---

## 许可

MIT，见 [LICENSE](LICENSE)。

二维码生成器与解码器均为原创实现，未捆绑任何第三方二维码库。