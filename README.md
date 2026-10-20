# ImageTrans Android

ImageTrans 的 Android 客户端。当前版本先实现**图片翻译**；拍照翻译、截屏翻译后续再加。

## 已实现

- 从相册 / 文件选择一张图片并翻译。
- **ImageTrans 服务器模式**：调用 `POST {serverURL}/translate`，以 `withoutImage=true` 取回文字框，再在 App 内用 WebView 渲染译文。
- **本地 OCR + OpenAI 兼容 API**：使用 rapidocr4j-android 在设备上做检测和识别，再把文本发给 OpenAI 兼容接口翻译。
- **仅识别模式**：只做 OCR，把识别结果覆盖回图片，方便调试。
- 译文保存到相册、分享。
- 原文 / 译文切换。
- 设置：服务器地址、项目名称、密码、OpenAI 地址/Key/模型、语言对、渲染 CSS、最小字号、原文背景处理方式。

## 架构

```
MainActivity（选择图片 / 设置）
        ↓
TranslateActivity
        ├─ ImageTrans 服务器（HTTP 表单）→ boxes 或服务端渲染图
        ├─ OcrEngine（rapidocr4j-android）→ boxes
        │       └─ TextBoxMerger（移植自扩展的 mergeTextBoxes）
        ├─ OpenAiTranslator（OpenAI 兼容 chat/completions）
        └─ Renderer（隐藏 WebView）
                ├─ assets/render.html
                ├─ assets/render.js（来自扩展 getImage.js 的渲染逻辑）
                └─ assets/html-to-image.js
```

- **OCR / 推理全部在原生侧完成**：rapidocr4j-android + OpenCV Android + ONNX Runtime Android。
- **WebView 只负责排版和栅格化**：复用扩展的 DOM 渲染（`renderTranslatedImageDOM`）和 Canvas 回退（`renderTranslatedImageCanvas`），支持扩展里的 CSS（竖排、RTL、圆角、描边、阴影、背景取色等）。
- 渲染结果通过 `AndroidRenderer` JavascriptInterface 以 data URL 返回原生，再显示、保存或分享。

## 构建

环境：

- Android Studio（本机：`G:\android-studio`）
- JDK 17（本机：`C:\Users\admin\.jdks\jbr-17.0.9`）
- Android SDK（本机：`C:\Users\admin\AppData\Local\Android\Sdk`）
- Gradle 8.11.1（wrapper 已配置）
- AGP 8.1.4

命令行构建：

```bat
set JAVA_HOME=C:\Users\admin\.jdks\jbr-17.0.9
G:\git\ImageTrans-Android\gradlew.bat -p G:\git\ImageTrans-Android assembleDebug
```

APK：

```
app/build/outputs/apk/debug/app-debug.apk
```

本地 AAR 放在 `app/libs/`：

- `rapidocr4j-android-1.0.0.aar`
- `opencv-4.9.0.aar`
- `onnxruntime-android-1.18.0.aar`

> 这三个 AAR 只包含 `arm64-v8a` 的 native 库，因此 `abiFilters` 只保留 `arm64-v8a`。x86_64 模拟器无法运行 OCR（可运行界面，但初始化 OpenCV/ONNX 会失败）。请使用 arm64 真机测试。

## 配置

### ImageTrans 服务器

在桌面端 ImageTrans 中打开 **工具 → 服务器**，启动服务器。手机上填写电脑的局域网地址，例如：

```
https://192.168.1.10:51043
```

注意：

- 不要填写 `local.basiccat.org`，它在手机上解析到手机自己。
- ImageTrans 本地服务器默认使用自签名 HTTPS 证书。App 不会全局关闭证书校验：
  - 推荐把服务器证书安装到 Android 的“受信任凭据 → 用户”中（App 的 network security config 已信任用户证书）；
  - 或者临时使用 HTTP 地址进行局域网测试。
- 项目名称（`displayName`）和密码按 ImageTrans 服务器里的设置填写；留空时 `displayName` 使用 `default`。

### OpenAI 兼容 API

填写 API 地址（不带 `/chat/completions`，例如 `https://api.deepseek.com/v1`）、Key 和模型名。默认提示词与浏览器扩展一致，要求只返回 JSON 数组。

### 语言

- ImageTrans 服务器模式：语言对也可以由服务器上的项目配置决定；App 里设置非 `auto` 时会一并发送 `sourceLang` / `targetLang`。
- 本地 OCR 模式：模型在 `app/src/main/assets/ocr/`，与浏览器扩展的 `paddleocr/` 一致。这些模型不提交到仓库，需要从扩展目录复制：
  - `PP-OCRv6_det_small.onnx`：DB 文本检测（默认语言）。
  - `rec.onnx` + `ppocrv6_dict.txt`：PP-OCRv6 识别，中、英、日效果最好。
  - `model.onnx`：YOLO 文本行检测。源语言设为日语时与扩展一致，先用它检测文字行，再逐行用 `rec.onnx` 识别（竖排行会旋转 90° 后再识别）。
  - rapidocr4j-android AAR 里的 PP-OCRv4 模型已不再使用，`app/libs/` 里的 AAR 可以是删掉模型（甚至只保留 `classes.jar`）的版本。
- 本地模式的“仅识别”不翻译，只把识别出的文字按原位置绘回图片。

### 排版

默认 CSS 与扩展一致：

```css
text-align: center;
border-radius: 10%;
```

支持 `font-family`、`font-weight`、`font-style`、`color`、`background-color`、`border-radius`、`text-shadow`、`-webkit-text-stroke-*`、`text-transform`、`writing-mode: vertical-rl`、`direction: rtl` 等。译文中的竖排、RTL 由 WebView 的 DOM 渲染处理，Canvas 仅作为回退。

## 已知限制 / 后续

- 拍照翻译、截屏翻译未实现。
- 本地 OCR 的语种范围受 PP-OCRv6 识别模型限制（中/英/日为主）；其他语言可通过 `OcrConfig.Rec.modelPath` / `recKeysPath` 接入对应语言的识别模型。
- 超大长图在 WebView / Canvas 上有尺寸和内存限制，目前按最长边 3500px、总像素 1200 万做缩放。
- 自签名证书目前依赖系统信任链；后续可以加“首次连接确认 + 证书指纹固定”的 TOFU 流程。
- iOS 版本尚未开始；原生 OCR 侧可以复用相应的 ONNX Runtime + 前后处理方案。

## 许可

本仓库沿用 GPL-3.0（见 `LICENSE`）。第三方组件：PaddleOCR / rapidocr4j（Apache-2.0）、OpenCV（Apache-2.0）、ONNX Runtime（MIT）、html-to-image（MIT）。如果后续发布到 Google Play / App Store，请自行确认各模型与依赖的许可证要求。
