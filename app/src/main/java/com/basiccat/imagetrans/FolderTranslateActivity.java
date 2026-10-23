package com.basiccat.imagetrans;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Folder translation.
 *
 * The user picks a directory through the Storage Access Framework; the app
 * translates every image directly inside it (no subdirectories) and writes
 * the results as PNG files to an "out" subdirectory. Processing stays in this
 * Activity: leaving the screen cancels the remaining files.
 */
public class FolderTranslateActivity extends LocalizedActivity {
    private static final String TAG = "FolderTranslate";
    private static final int REQUEST_DIRECTORY = 4001;
    private static final String OUT_DIR = "out";

    private Button btnPickFolder;
    private Button btnOpenOutput;
    private Button btnCancel;
    private TextView folderPath;
    private TextView statusText;
    private TextView countsText;
    private TextView resultText;
    private View progressPanel;
    private ProgressBar progressBar;
    private WebView renderWebView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor();
    private Renderer renderer;

    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile String cancelReason;
    /** The out directory of the current (or last) batch, once created. */
    private volatile DocumentFile outputDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_translate);
        applySystemBarInsets(R.id.appBar, false);

        btnPickFolder = findViewById(R.id.btnPickFolder);
        btnOpenOutput = findViewById(R.id.btnOpenOutput);
        btnCancel = findViewById(R.id.btnCancel);
        folderPath = findViewById(R.id.folderPath);
        statusText = findViewById(R.id.folderStatus);
        countsText = findViewById(R.id.folderCounts);
        resultText = findViewById(R.id.folderResult);
        progressPanel = findViewById(R.id.folderProgressPanel);
        progressBar = findViewById(R.id.folderProgress);
        renderWebView = findViewById(R.id.batchRenderWebView);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnPickFolder.setOnClickListener(v -> pickDirectory());
        btnOpenOutput.setOnClickListener(v -> openOutputDirectory());
        btnCancel.setOnClickListener(v -> cancelBatch(null));
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Processing is intentionally tied to this screen.
        if (running) cancelBatch(getString(R.string.folder_translate_stopped));
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true;
        batchExecutor.shutdownNow();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // --- directory picker -------------------------------------------------

    private void pickDirectory() {
        if (running) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_DIRECTORY);
        } catch (Exception exception) {
            ImageUtils.toast(this, getString(R.string.folder_translate_pick_failed,
                    ImageUtils.humanError(exception)));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DIRECTORY || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        } catch (Exception ignored) {
            // Some providers only grant temporary access; the session is enough.
        }
        DocumentFile root;
        try {
            root = DocumentFile.fromTreeUri(this, treeUri);
        } catch (Exception exception) {
            showFinalError(getString(R.string.folder_translate_read_failed,
                    ImageUtils.humanError(exception)));
            return;
        }
        if (root == null || !root.isDirectory() || !root.canRead()) {
            showFinalError(getString(R.string.folder_translate_read_failed,
                    getString(R.string.folder_translate_cannot_read)));
            return;
        }
        startBatch(root);
    }

    // --- batch ------------------------------------------------------------

    private void startBatch(DocumentFile root) {
        if (running) return;
        String name = root.getName();
        folderPath.setText(getString(R.string.folder_translate_selected,
                name == null || name.isEmpty() ? root.getUri().toString() : name));
        resultText.setText("");
        progressPanel.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        statusText.setText(R.string.folder_translate_preparing);
        countsText.setText("");
        btnPickFolder.setEnabled(false);
        btnOpenOutput.setVisibility(View.GONE);
        btnCancel.setEnabled(true);
        outputDir = null;

        running = true;
        cancelRequested = false;
        cancelReason = null;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (renderer == null) renderer = new Renderer(renderWebView);
        final Renderer batchRenderer = renderer;
        batchExecutor.execute(() -> runBatch(root, batchRenderer));
    }

    private void runBatch(DocumentFile root, Renderer batchRenderer) {
        int succeeded = 0;
        int failed = 0;
        boolean cancelled = false;
        List<String> errors = new ArrayList<>();
        try {
            List<DocumentFile> images = collectImages(root);
            if (images.isEmpty()) {
                postFinish(0, 0, false, null, errors,
                        getString(R.string.folder_translate_no_images));
                return;
            }
            DocumentFile outDir = ensureOutDir(root);
            outputDir = outDir;
            Set<String> usedNames = new HashSet<>();
            int total = images.size();
            postProgress(0, total, "", null, 0, 0);

            for (int i = 0; i < total; i++) {
                if (cancelRequested) {
                    cancelled = true;
                    break;
                }
                DocumentFile file = images.get(i);
                String fileName = safeName(file);
                postProgress(i + 1, total, fileName,
                        getString(R.string.folder_translate_reading), succeeded, failed);

                Bitmap decoded = null;
                Bitmap translated = null;
                try {
                    decoded = ImageUtils.decodeBitmap(this, file.getUri());
                    if (decoded == null) {
                        throw new Exception(getString(R.string.folder_translate_decode_failed));
                    }
                    final int progressIndex = i + 1;
                    final int currentSucceeded = succeeded;
                    final int currentFailed = failed;
                    BatchTranslator translator = new BatchTranslator(this, batchRenderer,
                            mainHandler, message -> postProgress(progressIndex, total,
                            fileName, message, currentSucceeded, currentFailed));
                    translated = translator.translate(decoded);
                    if (cancelRequested) {
                        cancelled = true;
                        break;
                    }
                    if (translated == null) {
                        throw new Exception(getString(R.string.folder_translate_render_failed));
                    }
                    String outputName = outputName(fileName, usedNames);
                    savePng(outDir, outputName, translated);
                    succeeded++;
                } catch (Throwable throwable) {
                    if (cancelRequested) {
                        cancelled = true;
                        break;
                    }
                    failed++;
                    String message = fileName + ": " + ImageUtils.humanError(throwable);
                    errors.add(message);
                    Log.w(TAG, "folder item failed: " + message, throwable);
                } finally {
                    recycle(decoded, translated);
                }
                postCounts(succeeded, failed);
                if (cancelRequested) {
                    cancelled = true;
                    break;
                }
            }
            if (cancelRequested) cancelled = true;
            postFinish(succeeded, failed, cancelled, cancelReason, errors, null);
        } catch (Throwable throwable) {
            Log.e(TAG, "folder batch failed", throwable);
            if (cancelRequested) {
                postFinish(succeeded, failed, true, cancelReason, errors, null);
            } else {
                postFinish(succeeded, failed, false, null, errors,
                        getString(R.string.folder_translate_failed,
                                ImageUtils.humanError(throwable)));
            }
        } finally {
            running = false;
        }
    }

    private List<DocumentFile> collectImages(DocumentFile root) {
        List<DocumentFile> images = new ArrayList<>();
        DocumentFile[] children = root.listFiles();
        for (DocumentFile file : children) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null || name.isEmpty()) continue;
            String type = null;
            try {
                type = file.getType();
            } catch (Exception ignored) {
                // Fall back to the extension.
            }
            if ((type != null && type.startsWith("image/")) || hasImageExtension(name)) {
                images.add(file);
            }
        }
        images.sort((a, b) -> safeName(a).compareToIgnoreCase(safeName(b)));
        return images;
    }

    private static boolean hasImageExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.US);
        switch (extension) {
            case "jpg":
            case "jpeg":
            case "png":
            case "webp":
            case "bmp":
            case "gif":
            case "heic":
            case "heif":
                return true;
            default:
                return false;
        }
    }

    private DocumentFile ensureOutDir(DocumentFile root) throws Exception {
        if (!root.canWrite()) {
            throw new Exception(getString(R.string.folder_translate_no_write));
        }
        DocumentFile out = root.findFile(OUT_DIR);
        if (out != null) {
            if (!out.isDirectory()) {
                throw new Exception(getString(R.string.folder_translate_out_not_dir));
            }
            if (!out.canWrite()) {
                throw new Exception(getString(R.string.folder_translate_no_write));
            }
            return out;
        }
        out = root.createDirectory(OUT_DIR);
        if (out == null || !out.isDirectory()) {
            throw new Exception(getString(R.string.folder_translate_out_failed));
        }
        return out;
    }

    private void savePng(DocumentFile outDir, String name, Bitmap bitmap) throws Exception {
        DocumentFile target = outDir.findFile(name);
        boolean created = false;
        if (target == null) {
            target = outDir.createFile("image/png", name);
            created = true;
        } else if (target.isDirectory()) {
            throw new Exception(getString(R.string.folder_translate_out_not_dir));
        }
        if (target == null) {
            throw new Exception(getString(R.string.folder_translate_save_failed, name));
        }
        try (OutputStream out = openForWrite(target.getUri())) {
            if (out == null) {
                throw new Exception(getString(R.string.folder_translate_save_failed, name));
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new Exception(getString(R.string.folder_translate_save_failed, name));
            }
        } catch (Throwable throwable) {
            if (created) {
                try {
                    target.delete();
                } catch (Exception ignored) {
                    // The provider may not allow deleting; the error is reported.
                }
            }
            throw throwable;
        }
    }

    private OutputStream openForWrite(Uri uri) throws IOException {
        try {
            // "wt" truncates an existing file so a rerun replaces the old PNG.
            return getContentResolver().openOutputStream(uri, "wt");
        } catch (Exception exception) {
            return getContentResolver().openOutputStream(uri);
        }
    }

    private static String outputName(String sourceName, Set<String> usedLowercaseNames) {
        String base = sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.trim().isEmpty()) base = "image";
        String candidate = base + ".png";
        int suffix = 2;
        while (!usedLowercaseNames.add(candidate.toLowerCase(Locale.US))) {
            candidate = base + "_" + suffix + ".png";
            suffix++;
        }
        return candidate;
    }

    private static String safeName(DocumentFile file) {
        String name = file.getName();
        return name == null || name.isEmpty() ? "image" : name;
    }

    private static void recycle(Bitmap first, Bitmap second) {
        if (first != null) first.recycle();
        if (second != null && second != first) second.recycle();
    }

    private void cancelBatch(String reason) {
        if (!running) return;
        cancelRequested = true;
        cancelReason = reason;
        btnCancel.setEnabled(false);
        statusText.setText(reason != null ? reason
                : getString(R.string.folder_translate_cancelling));
    }

    /**
     * Opens the out folder in a file manager. Directory support is not
     * standardized, so try the usual MIME types and fall back to the system
     * folder picker positioned at the folder.
     */
    private void openOutputDirectory() {
        DocumentFile out = outputDir;
        if (out == null) {
            ImageUtils.toast(this, getString(R.string.folder_translate_no_out_yet));
            return;
        }
        Uri uri = out.getUri();
        String[] types = {
                DocumentsContract.Document.MIME_TYPE_DIR,
                "resource/folder",
                "inode/directory"
        };
        for (String type : types) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
                return;
            } catch (Exception ignored) {
                // Try the next MIME type.
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
            startActivity(intent);
        } catch (Exception exception) {
            ImageUtils.toast(this, getString(R.string.folder_translate_open_failed,
                    ImageUtils.humanError(exception)));
        }
    }

    // --- UI updates -------------------------------------------------------

    private void postProgress(int index, int total, String fileName, String message,
                              int succeeded, int failed) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            progressBar.setIndeterminate(false);
            progressBar.setMax(Math.max(total, 1));
            progressBar.setProgress(index);
            if (index <= 0) {
                statusText.setText(R.string.folder_translate_preparing);
            } else {
                String text = getString(R.string.folder_translate_progress,
                        index, total, fileName);
                if (message != null && !message.isEmpty()) text += " · " + message;
                statusText.setText(text);
            }
            countsText.setText(index <= 0 ? ""
                    : getString(R.string.folder_translate_counts, succeeded, failed));
        });
    }

    private void postCounts(int succeeded, int failed) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            countsText.setText(getString(R.string.folder_translate_counts,
                    succeeded, failed));
        });
    }

    private void postFinish(int succeeded, int failed, boolean cancelled,
                            String reason, List<String> errors, String headline) {
        mainHandler.post(() -> {
            running = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (isFinishing() || isDestroyed()) return;
            progressPanel.setVisibility(View.GONE);
            btnPickFolder.setEnabled(true);
            btnCancel.setEnabled(false);
            btnOpenOutput.setVisibility(outputDir != null ? View.VISIBLE : View.GONE);

            StringBuilder text = new StringBuilder();
            if (headline != null) {
                text.append(headline);
            } else if (cancelled) {
                if (reason != null) text.append(reason).append('\n');
                text.append(getString(R.string.folder_translate_partial, succeeded, failed));
            } else {
                text.append(getString(R.string.folder_translate_done, succeeded, failed));
            }
            int shown = 0;
            for (String error : errors) {
                if (shown >= 8) {
                    text.append("\n…");
                    break;
                }
                text.append('\n').append(error);
                shown++;
            }
            resultText.setText(text.toString());
        });
    }

    private void showFinalError(String message) {
        ImageUtils.toast(this, message);
        resultText.setText(message);
    }
}
