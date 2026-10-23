# ImageTrans Android

中文 | [English](README.md)

ImageTrans 的 Android 版本，支持**图片翻译**、**拍照翻译**、**文件夹翻译**和**截屏翻译**。可以和[ImageTrans桌面版](https://www.basiccat.org/zh/imagetrans/)和[ImageTrans浏览器插件](https://github.com/xulihang/ImageTrans_chrome_extension)搭配使用。

## 下载

[下载 APK v0.1.1](https://github.com/xulihang/ImageTrans-Android/releases/tag/v0.1.1)

需要 Android 8.0（API 26）或更高版本，原生库仅支持 arm64-v8a。

## 已实现

- 从相册 / 文件选择一张图片并翻译。
- **拍照翻译**：CameraX 相机预览上有一个可拖动、可缩放四个角的选框；点「拍摄并翻译」后直接截取预览画面的一帧（不触发相机快门、不保存照片）并立即暂停相机，画面定格后再裁剪选框区域并翻译；识别结果用与图片翻译相同的原文/译文对话框显示，关闭对话框后相机恢复。
- **文件夹翻译**：通过 SAF 选择一个文件夹，直接翻译其中的图片（不包含子文件夹），结果以 PNG 保存到该文件夹的 `out` 子文件夹，文件名沿用原文件名（扩展名改为 `.png`）。处理结束后可以点「打开输出目录」在文件管理器中查看。处理在页面内进行，切到后台会停止。
- **ImageTrans 服务器模式**：调用 `POST {serverURL}/translate`。默认由服务器渲染并直接显示返回的图片（使用 ImageTrans 项目自身的排版）；也可以在设置里改为 App 内渲染，此时传 `withoutImage=true` 取回文字框，再用 App 的 WebView 和 CSS/字号设置排版。任一结果缺失时自动回退到另一种。
- **本地 OCR + OpenAI 兼容 API**：使用内置在仓库中的 rapidocr4j 源码在设备上做检测和识别，再把文本发给 OpenAI 兼容接口翻译。
- **本地 OCR + MyMemory（免费）**：无需 API Key，使用 MyMemory 公共翻译接口。按接口限制自动把长段落切成 500 字节以内的请求；默认使用内置联系邮箱（额度约 50000 字符/天），也可以在设置里填写自己的邮箱。超出额度后会提示额度用完。源语言选「自动检测」时按日语处理，目标语言选「自动检测」时按英语处理（与浏览器扩展一致）。
- **仅识别模式**：只做 OCR，把识别结果覆盖回图片，方便调试。
- **长条漫**：解码时保留长图比例（不再压到最长边 3500px）；OCR 按扩展的规则以 4×宽度为一块切分识别；渲染也分块进行再纵向拼接，避开 WebView/Canvas 的尺寸和内存限制。
- 译文保存到相册（`Pictures/ImageTrans`）、分享。
- 原文 / 译文切换。
- 结果图默认完整显示在屏幕内（整张图都可见），可双指或双击缩放；完整显示时左右滑动可切换原文/译文。放大后拖动图片会跟随手指，快速甩动会再滑一段（最多两屏）；完整显示时只有快速左右甩动才会切换原文/译文，慢慢拖动不会触发。
- **点击文字框查看原文/译文**：图片翻译页和截屏翻译的选框里，点击文字框弹出原文和译文；主界面里可长按选中部分文字复制（系统菜单），截屏翻译的浮窗里系统选择菜单不显示，改为“复制所选 / 复制全部”。
- **截屏翻译悬浮窗**：悬浮球 + 选框常驻；点悬浮球上的「翻译」截取选框区域并翻译，译文显示在选框内；选框可拖动、缩放，框外屏幕不受影响、照常使用其他 App。
  - **Android 11 及以上**通过无障碍服务（`AccessibilityService.takeScreenshot`）截屏：只需在系统无障碍设置里开启一次「ImageTrans」，之后可以随意切换应用、随时截屏，不再弹授权。该服务只用于截屏，不读取屏幕内容。
  - **Android 10** 没有无障碍截屏，使用系统的屏幕捕获（MediaProjection）授权：打开悬浮球时授权一次，之后再复用一段时间。
- **翻译服务器（浏览器扩展用）**：在后台提供 `POST /translate`（HTTP，端口 51042），供 ImageTrans 浏览器扩展的「ImageTrans 服务器」模式调用。OCR 在手机上完成，文字行合并为段落后返回文字框；App 设为「本地 OCR + OpenAI」或「本地 OCR + MyMemory」时同时返回译文，其他方式只返回原文，由扩展自己翻译。同一台手机上的 Edge/Firefox 扩展，或电脑上的浏览器通过 `adb reverse tcp:51042 tcp:51042` 转发后，直接填 `http://127.0.0.1:51042`，不依赖 Wi-Fi 或移动数据；同一网络中的电脑也可以使用手机的局域网 IP。通知栏常驻显示地址，可直接停止服务。
- **界面语言**：设置里可以选择「跟随系统 / 中文 / English」，默认跟随系统（系统为英文时显示英文，其他语言显示中文）；切换后会重启主界面使所有页面生效。
- 设置：翻译方式（ImageTrans 服务器 / MyMemory / OpenAI / 仅识别）、服务器地址、项目名称、密码、服务器渲染方式（服务端渲染 / App 内渲染）、MyMemory 联系邮箱、OpenAI 地址/Key/模型、语言对、界面语言、渲染 CSS、最小字号、原文背景处理方式。

## 架构

```
MainActivity（选择图片 / 设置）
        ↓
TranslateActivity
        ├─ ImageTrans 服务器（HTTP 表单）→ boxes 或服务端渲染图
        ├─ OcrEngine（内置 rapidocr4j）→ boxes
        │       └─ TextBoxMerger（移植自扩展的 mergeTextBoxes）
        ├─ OpenAiTranslator（OpenAI 兼容 chat/completions）
        ├─ MyMemoryTranslator（免费接口，自动分段）
        └─ Renderer（隐藏 WebView）
                ├─ assets/render.html
                ├─ assets/render.js（来自扩展 getImage.js 的渲染逻辑）
                └─ assets/html-to-image.js
```

- **OCR / 推理全部在原生侧完成**：rapidocr4j（源码在 `app/src/main/java/com/basiccat/imagetrans/rapidocr/`）+ OpenCV Android + ONNX Runtime Android。
- **截屏**：`ScreenshotAccessibilityService`（`takeScreenshot`，Android 11+）；Android 10 通过 `ProjectionPermissionActivity` 走 MediaProjection。
- **WebView 只负责排版和栅格化**：复用扩展的 DOM 渲染（`renderTranslatedImageDOM`）和 Canvas 回退（`renderTranslatedImageCanvas`），支持扩展里的 CSS（竖排、RTL、圆角、描边、阴影、背景取色等）。
- 渲染结果通过 `AndroidRenderer` JavascriptInterface 以 data URL 返回原生，再显示、保存或分享。

## 构建

环境：

- Android Studio
- JDK 17 或更高（Android Studio 自带的 JBR 即可）
- Android SDK，包含 platform 36 和 build-tools 36
- Gradle 8.11.1（wrapper 已配置）
- AGP 8.9.1

命令行构建：

```bat
set JAVA_HOME=<JDK 17 或更高版本的路径>
gradlew.bat assembleRelease
```

APK（未签名，安装前需要先签名）：

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

本地 AAR 放在 `app/libs/`：

- `opencv-4.14.0.aar`
- `onnxruntime-android-1.31.0.aar`

rapidocr4j 不再使用 AAR，而是以源码形式放在 app 模块中。

不在仓库里的文件可以从以下地址下载：

- [opencv-4.14.0.aar](https://repo1.maven.org/maven2/org/opencv/opencv/4.14.0/opencv-4.14.0.aar)（Maven Central）
- [onnxruntime-android-1.31.0.aar](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar)（Maven Central）
- [ocr-models.zip](https://github.com/xulihang/ImageTrans-Android/releases/download/assets/ocr-models.zip)：OCR 模型，解压到 `app/src/main/assets/ocr/`

> rapidocr4j 的源码（`io.github.hzkitty`、`de.lighti.clipper`，按 minSdk 26 编译）已内置在 `app/src/main/java/com/basiccat/imagetrans/rapidocr/` 中，替代要求 Android 10 的 `rapidocr4j-android` AAR，并与 App 共用同一套 OpenCV、ONNX Runtime 原生库。

> OpenCV 4.14.0、ONNX Runtime 1.31.0 和 CameraX 1.6.2（从 Google Maven 解析）均已按 16 KB 页面大小对齐，构建需要 AGP 8.9.1+ 和 compileSdk 36。Maven Central 的 AAR 包含多个 ABI，但 `abiFilters` 只会在 APK 中保留 `arm64-v8a`。x86_64 模拟器无法运行 OCR（可运行界面，但初始化 OpenCV/ONNX 会失败）。请使用 arm64 真机测试。

## 配置

### ImageTrans 服务器

安卓手机不能运行 ImageTrans 服务器本身，因此默认使用云端服务器：

```
https://service.basiccat.org:51043
```

如果需要连接电脑上的服务器，先在桌面端 ImageTrans 中打开 **工具 → 服务器** 启动服务器，手机上填写电脑的局域网地址，例如：

```
https://192.168.1.10:51043
```

注意：

- 不要填写 `local.basiccat.org`，它在手机上解析到手机自己。
- 电脑上的 ImageTrans 服务器默认使用自签名 HTTPS 证书。App 不会全局关闭证书校验：
  - 推荐把服务器证书安装到 Android 的“受信任凭据 → 用户”中（App 的 network security config 已信任用户证书）；
  - 或者临时使用 HTTP 地址进行局域网测试。
- 项目名称（`displayName`）和密码按 ImageTrans 服务器里的设置填写；留空时 `displayName` 使用 `default`。

### OpenAI 兼容 API

填写 API 地址（不带 `/chat/completions`，例如 `https://api.deepseek.com/v1`）、Key 和模型名。默认提示词与浏览器扩展一致，要求只返回 JSON 数组。

### 语言

- ImageTrans 服务器模式：语言对也可以由服务器上的项目配置决定；App 里设置非 `auto` 时会一并发送 `sourceLang` / `targetLang`。
- 本地 OCR 模式：模型在 `app/src/main/assets/ocr/`，与浏览器扩展的 `paddleocr/` 一致。这些模型不提交到仓库，需要从扩展目录复制：
  - `PP-OCRv6_det_small.onnx`：DB 文本检测（默认语言）。
  - `rec.onnx` + `ppocrv6_dict.txt`：PP-OCRv6 识别，中、英、日以及拉丁系语言使用这个内置模型。
  - `model.onnx`：YOLO 文本行检测。源语言设为日语时与扩展一致，先用它检测文字行，再逐行用 `rec.onnx` 识别（竖排行顺时针旋转 90° 后识别，按自右向左顺序）。
  - 模型首次使用时解压到应用私有目录 `files/models/`，因为 rapidocr4j 只接受文件路径。
- 韩语、阿拉伯语、俄语、泰语不在 PP-OCRv6 的覆盖范围内。与扩展的 `PADDLE_LANG_TO_MODEL` 一致，这四种语言首次识别时会从 ModelScope 下载对应的 PP-OCRv5 识别模型和词典（每套约 8–13 MB），保存到 `files/models/` 后复用；检测仍使用内置的 PP-OCRv6 DB 模型。下载进度显示在翻译页的状态栏，只有首次使用需要联网。
- 行方向分类（`useCls`）只在源语言为中文时启用，其他语言（韩、英、俄、阿、泰等）关闭：`ch_ppocr_mobile_v2.0_cls_infer.onnx` 是中文/英文行方向模型，会把正向的韩语行高置信度误判为 180°，导致整行被转反后丢弃。模型文件仍需保留，因为 rapidocr4j 初始化时会构造分类器。YOLO 识别流程不使用它。
- 本地模式的“仅识别”不翻译，通过 WebView 渲染器把原文按原位置绘回图片（与扩展一致，会按“原文背景处理”盖住原文）。

### 排版

默认 CSS 与扩展一致：

```css
text-align: center;
border-radius: 10%;
```

支持 `font-family`、`font-weight`、`font-style`、`color`、`background-color`、`border-radius`、`text-shadow`、`-webkit-text-stroke-*`、`text-transform`、`writing-mode: vertical-rl`、`direction: rtl` 等。译文中的竖排、RTL 由 WebView 的 DOM 渲染处理，Canvas 仅作为回退。

## 已知限制 / 后续

### 截屏翻译 / 翻译服务器在后台的保活设置

截屏翻译的悬浮球和「翻译服务器」（浏览器扩展用）都依赖前台服务在后台长期运行。

- **浏览器扩展使用 `http://127.0.0.1:51042`**：适用于同一台手机，或电脑经 `adb reverse tcp:51042 tcp:51042` 转发。它走本机回环，不需要 Wi-Fi 或移动数据，系统的后台联网限制也不影响回环；但 App 进程仍必须存活。
- **同一网络中的电脑**可以填 `http://手机局域网IP:51042`。部分国产 ROM（ColorOS、HyperOS、EMUI 等）会在切到后台或熄屏后限制 App 的后台联网，表现为**进程和通知都还在，但电脑访问超时**。这种情况需要在系统设置里放行：

- **耗电管理 / 电池优化**：设置 → 电池 → 应用耗电管理（或“电池优化”），把 ImageTrans 设为「允许后台活动 / 不限制 / 不优化」。
- **应用启动管理**：设置 → 应用 → 应用启动管理，关闭 ImageTrans 的「自动管理」，并允许自启动、允许后台活动。
- **后台联网**：部分系统在「流量管理 / 联网管理」里单独控制后台数据，允许 ImageTrans 在后台使用 WLAN。
- **通知**：不要清除「ImageTrans 服务器运行中」这条通知；它被清除说明前台服务已停止。

App 已在请求期间标记为前台流量并持有 CPU 部分唤醒锁，但这只能减少、不能绕过系统级的后台网络限制。也可以用 adb 临时放行（重启或系统清理后可能失效）：

```bat
adb shell cmd netpolicy add restrict-background-whitelist <uid>
adb shell dumpsys deviceidle whitelist +com.basiccat.imagetrans
```

`<uid>` 是 `adb shell dumpsys package com.basiccat.imagetrans | findstr userId` 返回的 userId。

- 本地 OCR：中、英、日及拉丁系语言使用内置 PP-OCRv6；韩、阿、俄、泰使用首次联网下载的 PP-OCRv5 识别模型。其他语种仍需在扩展中补充对应模型。
- 长条漫（高度大于宽度 8 倍）：
  - 解码不再按最长边 3500px 缩放，而是保留宽度（上限 2000px）、总像素上限 1200 万，使用 `ImageDecoder` 在解码阶段直接缩放（Android 9 及以上；更低版本回退到 `BitmapFactory`，超长图可能内存不足）。
  - OCR：按 `4 × 宽度` 为一块切分（与扩展一致），每块缩到最长边 1500px 后单独识别，再把坐标映射回整图。切块长边固定为 1500px，因此块高与宽度的比例直接决定识别时每块的宽度（1500 / 比例）；用 8×宽度时块宽只有约 187px，韩语等小字号文字会识别错误。切块边界上的文字会被拆成两段识别。
  - 渲染按文本框之间的空隙选择切点，单块不超过 600 万像素且最长边不超过 4096px，逐块交给 WebView 渲染后在原生侧拼接，避免整张长图撑爆 canvas 内存。
  - 最终输出图仍受 1200 万像素上限约束；极端长图会按比例缩小。
- 普通图片在 WebView / Canvas 上有尺寸和内存限制，目前按最长边 3500px、总像素 1200 万做缩放。
- 自签名证书目前依赖系统信任链；后续可以加“首次连接确认 + 证书指纹固定”的 TOFU 流程。
- iOS 版本尚未开始；原生 OCR 侧可以复用相应的 ONNX Runtime + 前后处理方案。

## 许可

本仓库沿用 GPL-3.0（见 `LICENSE`）。第三方组件：PaddleOCR / rapidocr4j（Apache-2.0）、OpenCV（Apache-2.0）、ONNX Runtime（MIT）、html-to-image（MIT）。如果后续发布到 Google Play / App Store，请自行确认各模型与依赖的许可证要求。
