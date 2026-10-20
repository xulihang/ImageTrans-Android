package com.basiccat.imagetrans;

import org.opencv.android.OpenCVLoader;

/** Loads the OpenCV AAR's native library (libopencv_java4.so) before OCR starts. */
final class RootlessOpencvLoader {
    private static boolean loaded;

    private RootlessOpencvLoader() {
    }

    static synchronized void load() {
        if (!loaded) {
            loaded = OpenCVLoader.initLocal();
        }
    }
}
