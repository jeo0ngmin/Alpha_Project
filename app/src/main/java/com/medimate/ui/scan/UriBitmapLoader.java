package com.medimate.ui.scan;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;

/** 촬영·갤러리 Uri를 OCR에 넘길 Bitmap으로 읽는다. 메인 스레드에서 부르지 않는다. */
final class UriBitmapLoader {

    // 카메라 원본(4,000px 이상)은 메모리만 많이 쓰고 인식률은 더 좋아지지 않는다
    private static final int MAX_EDGE_PX = 2000;

    private UriBitmapLoader() {
    }

    static Bitmap load(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // EXIF 회전이 자동 반영된다. ML Kit은 하드웨어 비트맵을 받지 못하므로 소프트웨어로 만든다.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int longEdge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sampleSize = 1;
                while (longEdge / sampleSize > MAX_EDGE_PX) {
                    sampleSize *= 2;
                }
                decoder.setTargetSampleSize(sampleSize);
            });
        }
        // API 26~27: 회전·축소 없이 읽는다
        return MediaStore.Images.Media.getBitmap(resolver, uri);
    }
}
