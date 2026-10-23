# ImageTrans Android

[中文](README_zh.md) | English

The Android version of ImageTrans, supporting **image translation**, **camera translation**, **folder translation** and **screenshot translation**. It can also work together with [ImageTrans Desktop](https://www.basiccat.org/imagetrans/) and the [ImageTrans browser extension](https://github.com/xulihang/ImageTrans_chrome_extension).

## Download

[Download APK v0.1.1](https://github.com/xulihang/ImageTrans-Android/releases/tag/v0.1.1)

Requires Android 8.0 (API 26) or later; the native libraries are arm64-v8a only.

## Features

- Pick an image from the gallery / file picker and translate it.
- **Camera translation**: A draggable, corner-resizable selection box is overlaid on the CameraX preview. Tapping "Capture and Translate" grabs a frame directly from the preview (no shutter, no photo saved) and unbinds the camera right away, so the preview freezes while the selection is cropped and translated; results are shown in the same original/translation dialog as image translation. The camera resumes when the dialog closes.
- **Folder translation**: Select a folder via SAF and translate the images in it directly (subfolders are not included). Results are saved as PNG files in an `out` subfolder, keeping the original file names (extension changed to `.png`). When processing finishes, tap "Open output folder" to view it in the file manager. Processing runs within the page and stops if you switch to the background.
- **ImageTrans server mode**: Calls `POST {serverURL}/translate`. By default the server renders and directly returns the image (using the layout of the ImageTrans project itself); you can switch to in-app rendering in settings, in which case `withoutImage=true` is sent to get the text boxes back, and the App's WebView lays them out using its CSS / font size settings. If either result is missing, it automatically falls back to the other.
- **Local OCR + OpenAI-compatible API**: Uses the rapidocr4j sources bundled in this repository for on-device detection and recognition, then sends the text to an OpenAI-compatible endpoint for translation.
- **Local OCR + MyMemory (free)**: No API key needed; uses the public MyMemory translation API. Long paragraphs are automatically split into requests under 500 bytes to comply with the API limits; a built-in contact email is used by default (quota of about 50,000 characters/day), and you can enter your own email in settings. You are notified when the quota is exhausted. With source language "Auto", Japanese is assumed; with target language "Auto", English is assumed (same as the browser extension).
- **Recognize-only mode**: Performs OCR only and draws the recognized text back onto the image, useful for debugging.
- **Long webtoon strips**: Aspect ratio is preserved when decoding (no longer downscaled to a 3500px longest side); OCR splits the image into blocks of 4× width following the extension's rules; rendering is also done block by block and stitched vertically, avoiding WebView/Canvas size and memory limits.
- Save translated images to the gallery (`Pictures/ImageTrans`, which also shows up there right away on Android 8/9) and share them.
- **Font scale** in settings (default 100%): the font size fitted for each text box is multiplied by it; a larger value grows the box around the text.
- Toggle between original and translated text.
- Open the result fitted inside the screen (the whole image is visible); pinch or double-tap to zoom. At the fitted size a fast sideways flick (or drag) switches original/translation and a slow drag does nothing; zoomed in, dragging moves the image with the finger and a fast flick coasts further (up to two screens).
- **Tap a text box to view original/translation**: On the image translation page and inside the screenshot translation selection, tapping a text box shows the original and translated text; on the main screen you can long-press to select and copy part of the text (system menu), while in the screenshot translation overlay the system selection menu is not shown — instead "Copy selected / Copy all" is provided.
- **Screenshot translation overlay**: A floating button plus a persistent selection box; tapping "Translate" on the floating button captures the selected area and translates it, with the translation displayed inside the selection. The selection can be dragged and resized, and the rest of the screen is unaffected so you can keep using other apps.
  - **Android 11+** captures through an accessibility service (`AccessibilityService.takeScreenshot`). Turn on "ImageTrans" once in the system accessibility settings; after that you can switch apps freely and capture at any time without a prompt. The service is used only for screenshots and does not read screen content.
  - **Android 10** has no accessibility screenshots, so it uses the screen-capture (MediaProjection) consent instead: one grant when the floating button is turned on, reused for a while.
- **Translation server (for the browser extension)**: Provides `POST /translate` in the background (HTTP, port 51042) for the extension's "ImageTrans Server" mode. OCR is done on the phone, text lines are merged into paragraphs, and text boxes are returned; when the App is set to "Local OCR + OpenAI" or "Local OCR + MyMemory", translations are returned as well, otherwise only the original text is returned and the extension translates it itself. For Edge/Firefox extensions on the same phone, or a browser on a computer forwarded via `adb reverse tcp:51042 tcp:51042`, enter `http://127.0.0.1:51042` directly — no Wi-Fi or mobile data required; a computer on the same network can also use the phone's LAN IP. The address is shown persistently in the notification, from which the server can be stopped.
- **Interface language**: Settings offer "Follow system / 中文 / English", defaulting to the system language (English on English systems, Chinese otherwise); switching restarts the main screen so it takes effect everywhere.
- Settings: translation method (ImageTrans server / MyMemory / OpenAI / recognize only), server address, project name, password, rendering mode (server-side / in-app), MyMemory contact email, OpenAI endpoint/key/model, language pair, interface language, rendering CSS, minimum font size, font scale, original text background handling.

## Architecture

```
MainActivity (pick image / settings)
        ↓
TranslateActivity
        ├─ ImageTrans Server (HTTP form) → boxes or server-rendered image
        ├─ OcrEngine (bundled rapidocr4j) → boxes
        │       └─ TextBoxMerger (ported from the extension's mergeTextBoxes)
        ├─ OpenAiTranslator (OpenAI-compatible chat/completions)
        ├─ MyMemoryTranslator (free API, automatic chunking)
        └─ Renderer (hidden WebView)
                ├─ assets/render.html
                ├─ assets/render.js (rendering logic from the extension's getImage.js)
                └─ assets/html-to-image.js
```

- **All OCR / inference happens natively**: rapidocr4j (sources in `app/src/main/java/com/basiccat/imagetrans/rapidocr/`) + OpenCV Android + ONNX Runtime Android.
- **Screenshot capture**: `ScreenshotAccessibilityService` (`takeScreenshot`, Android 11+), or MediaProjection through `ProjectionPermissionActivity` on Android 10.
- **The WebView only handles layout and rasterization**: reuses the extension's DOM rendering (`renderTranslatedImageDOM`) and Canvas fallback (`renderTranslatedImageCanvas`), supporting the extension's CSS (vertical writing, RTL, rounded corners, stroke, shadow, background color sampling, etc.).
- Rendering results are returned to native code as data URLs via the `AndroidRenderer` JavascriptInterface, then displayed, saved or shared.

## Build

Environment:

- Android Studio
- JDK 17 or later (the Android Studio bundled JBR works)
- Android SDK with platform 36 and build-tools 36
- Gradle 8.11.1 (wrapper configured)
- AGP 8.9.1

Command-line build:

```bat
set JAVA_HOME=<path to a JDK 17+>
gradlew.bat assembleRelease
```

APK (unsigned; sign it before installing):

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

Local AARs are placed in `app/libs/`:

- `opencv-4.14.0.aar`
- `onnxruntime-android-1.31.0.aar`

rapidocr4j is not taken from an AAR: its sources are part of the app module.

Files not committed to the repository can be downloaded from:

- [opencv-4.14.0.aar](https://repo1.maven.org/maven2/org/opencv/opencv/4.14.0/opencv-4.14.0.aar) (Maven Central)
- [onnxruntime-android-1.31.0.aar](https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.31.0/onnxruntime-android-1.31.0.aar) (Maven Central)
- [ocr-models.zip](https://github.com/xulihang/ImageTrans-Android/releases/download/assets/ocr-models.zip): OCR models to extract into `app/src/main/assets/ocr/`

> The rapidocr4j sources (`io.github.hzkitty`, `de.lighti.clipper`, compiled at minSdk 26) are bundled in `app/src/main/java/com/basiccat/imagetrans/rapidocr/` instead of the published `rapidocr4j-android` AAR, which requires Android 10. They share the app's OpenCV and ONNX Runtime native libraries.

> OpenCV 4.14.0 and ONNX Runtime 1.31.0 are 16 KB page aligned, as is CameraX 1.6.2 (resolved from Google Maven). This requires AGP 8.9.1+ and compileSdk 36. The Maven Central AARs contain several ABIs, but `abiFilters` keeps only `arm64-v8a` in the APK. OCR cannot run on x86_64 emulators (the UI runs, but initializing OpenCV/ONNX will fail). Please test on a real arm64 device.

## Configuration

### ImageTrans Server

Android phones cannot run the ImageTrans server itself, so a cloud server is used by default:

```
https://service.basiccat.org:51043
```

To connect to a server on your computer, first start the server in ImageTrans Desktop via **Tools → Server**, then enter the computer's LAN address on the phone, for example:

```
https://192.168.1.10:51043
```

Notes:

- Do not enter `local.basiccat.org`, which resolves to the phone itself on a phone.
- The ImageTrans server on your computer uses a self-signed HTTPS certificate by default. The App does not disable certificate validation globally:
  - Recommended: install the server certificate into Android's "Trusted credentials → User" store (the App's network security config already trusts user certificates);
  - Alternatively, temporarily use an HTTP address for LAN testing.
- Enter the project name (`displayName`) and password as configured in the ImageTrans server; if left empty, `displayName` uses `default`.

### OpenAI-compatible API

Enter the API endpoint (without `/chat/completions`, e.g. `https://api.deepseek.com/v1`), key and model name. The default prompt matches the browser extension and requires a JSON array as the only output.

### Languages

- ImageTrans server mode: the language pair can also be determined by the project configuration on the server; if a non-`auto` value is set in the App, `sourceLang` / `targetLang` are sent along.
- Local OCR mode: models live in `app/src/main/assets/ocr/`, matching the extension's `paddleocr/`. These models are not committed to the repository and need to be copied from the extension directory:
  - `PP-OCRv6_det_small.onnx`: DB text detection (default language).
  - `rec.onnx` + `ppocrv6_dict.txt`: PP-OCRv6 recognition, used by the built-in model for Chinese, English, Japanese and Latin-script languages.
  - `model.onnx`: YOLO text line detection. When the source language is Japanese, as in the extension, it is used first to detect text lines, then each line is recognized by `rec.onnx` (vertical lines are rotated 90° clockwise before recognition, in right-to-left order).
  - On first use, models are extracted to the app-private directory `files/models/` because rapidocr4j only accepts file paths.
- Korean, Arabic, Russian and Thai are not covered by PP-OCRv6. Matching the extension's `PADDLE_LANG_TO_MODEL`, on first recognition these four languages download the corresponding PP-OCRv5 recognition model and dictionary from ModelScope (about 8–13 MB each) and cache them in `files/models/` for reuse; detection still uses the built-in PP-OCRv6 DB model. Download progress is shown in the status bar of the translation page, and a network connection is only needed on first use.
- Line orientation classification (`useCls`) is enabled only when the source language is Chinese and disabled for other languages (Korean, English, Russian, Arabic, Thai, etc.): `ch_ppocr_mobile_v2.0_cls_infer.onnx` is a Chinese/English line orientation model that misclassifies upright Korean lines as 180° with high confidence, causing whole lines to be flipped and dropped. The model file must still be kept, because rapidocr4j constructs the classifier on initialization. The YOLO recognition pipeline does not use it.
- "Recognize only" in local mode does not translate; the original text is drawn back onto the image at its original position via the WebView renderer (same as the extension, covering the original text according to "original text background handling").

### Layout

The default CSS matches the extension:

```css
text-align: center;
border-radius: 10%;
```

Supported properties include `font-family`, `font-weight`, `font-style`, `color`, `background-color`, `border-radius`, `text-shadow`, `-webkit-text-stroke-*`, `text-transform`, `writing-mode: vertical-rl`, `direction: rtl`, etc. Vertical writing and RTL in translations are handled by the WebView's DOM rendering, with Canvas only as a fallback.

Font size is fitted to each text box automatically. Two settings adjust it: the minimum font size (px, used when the fitted size would be smaller) and the font scale (%, default 100, multiplied after fitting; 120 makes the text 20% larger and the box grows to fit it). Both apply to in-app rendering, not to images rendered by the ImageTrans server.

## Known limitations / future work

### Keeping screenshot translation / translation server alive in the background

The screenshot translation floating button and the "translation server" (for the browser extension) both rely on a foreground service running in the background for a long time.

- **Browser extension using `http://127.0.0.1:51042`**: Works on the same phone, or on a computer via `adb reverse tcp:51042 tcp:51042`. It uses the loopback interface, requiring no Wi-Fi or mobile data, and the system's background network restrictions do not affect loopback; however, the App process must still be alive.
- **A computer on the same network** can enter `http://<phone LAN IP>:51042`. Some Chinese ROMs (ColorOS, HyperOS, EMUI, etc.) restrict background network access for apps once they are in the background or the screen is off — the **process and notification are still there, but connections from the computer time out**. In that case, allow the App in system settings:

- **Battery management / battery optimization**: Settings → Battery → App battery management (or "Battery optimization"), set ImageTrans to "Allow background activity / Unrestricted / Don't optimize".
- **App launch management**: Settings → Apps → App launch management, turn off "Auto manage" for ImageTrans, and allow auto-start and background activity.
- **Background data**: Some systems separately control background data under "Data management / Network management"; allow ImageTrans to use WLAN in the background.
- **Notification**: Do not dismiss the "ImageTrans server running" notification; if it is dismissed, the foreground service has stopped.

The App marks itself as foreground traffic during requests and holds a partial CPU wake lock, but this can only reduce, not bypass, system-level background network restrictions. You can also temporarily allow it via adb (may be reset after a reboot or system cleanup):

```bat
adb shell cmd netpolicy add restrict-background-whitelist <uid>
adb shell dumpsys deviceidle whitelist +com.basiccat.imagetrans
```

`<uid>` is the userId returned by `adb shell dumpsys package com.basiccat.imagetrans | findstr userId`.

- Local OCR: Chinese, English, Japanese and Latin-script languages use the built-in PP-OCRv6; Korean, Arabic, Russian and Thai use PP-OCRv5 recognition models downloaded on first use. Other languages still require adding the corresponding models in the extension.
- Long webtoon strips (height greater than 8× width):
  - Decoding no longer scales by a 3500px longest side; instead the width is preserved (max 2000px) with a total pixel cap of 12 million, using `ImageDecoder` to scale directly during decoding (Android 9+; older releases fall back to `BitmapFactory`, so very long strips can run out of memory).
  - OCR: split into blocks of `4 × width` (same as the extension), each block scaled to a 1500px longest side and recognized separately, then coordinates are mapped back to the full image. Since the block's long side is fixed at 1500px, the block's height-to-width ratio directly determines the width of each block during recognition (1500 / ratio); with 8× width the block width would be only about 187px, and small text such as Korean would be misrecognized. Text on block boundaries is split into two parts.
  - Rendering picks split points based on gaps between text boxes, with each block no more than 6 million pixels and a longest side no more than 4096px; blocks are rendered by the WebView one by one and stitched on the native side, preventing the full long image from exhausting canvas memory.
  - The final output image is still limited to 12 million pixels; extremely long images are scaled down proportionally.
- Regular images are subject to size and memory limits in WebView / Canvas, and are currently scaled to a 3500px longest side and 12 million total pixels.
- Self-signed certificates currently rely on the system trust chain; a TOFU flow ("confirm on first connection + certificate pinning") could be added later.

## License

This repository is licensed under GPL-3.0 (see `LICENSE`). Third-party components: PaddleOCR / rapidocr4j (Apache-2.0), OpenCV (Apache-2.0), ONNX Runtime (MIT), html-to-image (MIT).
