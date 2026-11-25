package com.medimate.pill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

/**
 * 업로드용 JPEG 만들기: EXIF 회전 적용, 긴 변 1600px로 축소.
 * 추론 서버도 1600px로 줄여서 처리하므로 그 이상은 전송량만 늘린다.
 */
public final class ImageCompressor {

    public static final int MAX_SIDE = 1600;
    private static final int JPEG_QUALITY = 90;

    private ImageCompressor() {
    }

    public static byte[] toUploadJpeg(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(resolver, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("이미지를 읽을 수 없습니다: " + uri);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE);
        Bitmap decoded;
        try (InputStream in = open(resolver, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) {
            throw new IOException("이미지를 해석할 수 없습니다: " + uri);
        }

        int rotation;
        try (InputStream in = open(resolver, uri)) {
            rotation = new ExifInterface(in).getRotationDegrees();
        }
        int[] target = targetSize(decoded.getWidth(), decoded.getHeight(), MAX_SIDE);
        Matrix m = new Matrix();
        m.postScale((float) target[0] / decoded.getWidth(), (float) target[1] / decoded.getHeight());
        m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf);
        if (out != decoded) {
            out.recycle();
        }
        decoded.recycle();
        return buf.toByteArray();
    }

    /** 디코딩 후에도 긴 변이 maxSide 이상 남는 가장 큰 2의 거듭제곱 (메모리 절약용). */
    static int sampleSize(int width, int height, int maxSide) {
        int longSide = Math.max(width, height);
        int sample = 1;
        while (longSide / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        return sample;
    }

    /** 비율을 유지하며 긴 변을 maxSide 이하로 맞춘 크기. 이미 작으면 그대로. */
    static int[] targetSize(int width, int height, int maxSide) {
        int longSide = Math.max(width, height);
        if (longSide <= maxSide) {
            return new int[]{width, height};
        }
        double scale = (double) maxSide / longSide;
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            throw new IOException("열 수 없는 사진입니다: " + uri);
        }
        return in;
    }
}
