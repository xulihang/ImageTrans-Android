# ImageTrans Android

ImageTrans 的 Android 客户端。当前版本先实现**图片翻译**；拍照翻译、截屏翻译后续再加。

## 已实现

- 从相册 / 文件选择一张图片并翻译。
- **ImageTrans 服务器模式**：调用 `POST {serverURL}/translate`，以 `withoutImage=true` 取回文字框，再在 App 内用 WebView 渲染译文。
- **本地 OCR + OpenAI 兼容 API**：使用 rapidocr4j-android 在设备上做检测和识别，再把文本发给 OpenAI 兼容接口翻译。
- **仅识别模式**：只做 OCR，把识别结果覆盖回图片，方便调试。
- **长条漫**：解码时保留长图比例（不再压到最长边 3500px）；OCR 按扩展的规则以 4×宽度为一块切分识别；渲染也分块进行再纵向拼接，避开 WebView/Canvas 的尺寸和内存限制。
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
  - `rec.onnx` + `ppocrv6_dict.txt`：PP-OCRv6 识别，中、英、日以及拉丁系语言使用这个内置模型。
  - `model.onnx`：YOLO 文本行检测。源语言设为日语时与扩展一致，先用它检测文字行，再逐行用 `rec.onnx` 识别（竖排行顺时针旋转 90° 后识别，按自右向左顺序）。
  - 模型首次使用时解压到应用私有目录 `files/models/`，因为 rapidocr4j 只接受文件路径。
- 韩语、阿拉伯语、俄语、泰语不在 PP-OCRv6 的覆盖范围内。与扩展的 `PADDLE_LANG_TO_MODEL` 一致，这四种语言首次识别时会从 ModelScope 下载对应的 PP-OCRv5 识别模型和词典（每套约 8–13 MB），保存到 `files/models/` 后复用；检测仍使用内置的 PP-OCRv6 DB 模型。下载进度显示在翻译页的状态栏，只有首次使用需要联网。
- DB 检测流程启用了方向分类（`useCls`），需要 `ch_ppocr_mobile_v2.0_cls_infer.onnx`；YOLO 识别流程不使用它。
- 本地模式的“仅识别”不翻译，通过 WebView 渲染器把原文按原位置绘回图片（与扩展一致，会按“原文背景处理”盖住原文）。

### 排版

默认 CSS 与扩展一致：

```css
text-align: center;
border-radius: 10%;
```

支持 `font-family`、`font-weight`、`font-style`、`color`、`background-color`、`border-radius`、`text-shadow`、`-webkit-text-stroke-*`、`text-transform`、`writing-mode: vertical-rl`、`direction: rtl` 等。译文中的竖排、RTL 由 WebView 的 DOM 渲染处理，Canvas 仅作为回退。

## 已知限制 / 后续

- 拍照翻译、截屏翻译未实现。
- 本地 OCR：中、英、日及拉丁系语言使用内置 PP-OCRv6；韩、阿、俄、泰使用首次联网下载的 PP-OCRv5 识别模型。其他语种仍需在扩展中补充对应模型。
- 长条漫（高度大于宽度 4 倍）：
  - 解码不再按最长边 3500px 缩放，而是保留宽度（上限 2000px）、总像素上限 1200 万，使用 `ImageDecoder` 在解码阶段直接缩放。
  - OCR 与扩展一致：按 `4 × 宽度` 为一块切分，每块缩到最长边 1500px 后单独识别，再把坐标映射回整图。切块边界上的文字会被拆成两段识别。
  - 渲染按文本框之间的空隙选择切点，单块不超过 600 万像素且最长边不超过 4096px，逐块交给 WebView 渲染后在原生侧拼接，避免整张长图撑爆 canvas 内存。
  - 最终输出图仍受 1200 万像素上限约束；极端长图会按比例缩小。
- 普通图片在 WebView / Canvas 上有尺寸和内存限制，目前按最长边 3500px、总像素 1200 万做缩放。
- 自签名证书目前依赖系统信任链；后续可以加“首次连接确认 + 证书指纹固定”的 TOFU 流程。
- iOS 版本尚未开始；原生 OCR 侧可以复用相应的 ONNX Runtime + 前后处理方案。

## 许可

本仓库沿用 GPL-3.0（见 `LICENSE`）。第三方组件：PaddleOCR / rapidocr4j（Apache-2.0）、OpenCV（Apache-2.0）、ONNX Runtime（MIT）、html-to-image（MIT）。如果后续发布到 Google Play / App Store，请自行确认各模型与依赖的许可证要求。
