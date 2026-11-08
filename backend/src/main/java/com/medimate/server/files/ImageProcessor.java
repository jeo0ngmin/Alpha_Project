package com.medimate.server.files;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

/** 업로드 이미지를 안전하게 읽어 EXIF 방향을 적용하고, 위치 정보 등 메타데이터 없는 JPEG으로 다시 쓴다. */
final class ImageProcessor {

    static final class Unsupported extends Exception {
    }

    static final class TooManyPixels extends Exception {
    }

    private static final long MAX_PIXELS = 50_000_000L;
    private static final float JPEG_QUALITY = 0.92f; // 각인 글자가 뭉개지지 않을 정도

    static {
        ImageIO.setUseCache(false);
    }

    private ImageProcessor() {
    }

    static boolean hasSupportedSignature(byte[] b) {
        boolean jpeg = b.length > 3 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xD8 && (b[2] & 255) == 0xFF;
        boolean png = b.length > 8 && (b[0] & 255) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        return jpeg || png;
    }

    /** 픽셀을 풀기 전에 가로·세로부터 확인한다 (작은 파일이 거대한 이미지로 풀리는 공격 방지). */
    static BufferedImage decode(byte[] bytes) throws Unsupported, TooManyPixels {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new Unsupported();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new TooManyPixels();
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new Unsupported();
        }
    }

    static int exifOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // EXIF가 깨져 있어도 사진은 쓸 수 있다
        }
        return 1;
    }

    /** EXIF 방향대로 돌린 RGB 이미지를 돌려준다. 투명한 부분은 흰색이 된다. */
    static BufferedImage upright(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> t.setTransform(-1, 0, 0, 1, w, 0);
            case 3 -> t.setTransform(-1, 0, 0, -1, w, h);
            case 4 -> t.setTransform(1, 0, 0, -1, 0, h);
            case 5 -> t.setTransform(0, 1, 1, 0, 0, 0);
            case 6 -> t.setTransform(0, 1, -1, 0, h, 0);
            case 7 -> t.setTransform(0, -1, -1, 0, h, w);
            case 8 -> t.setTransform(0, -1, 1, 0, 0, w);
            default -> {
            }
        }
        boolean swap = orientation >= 5 && orientation <= 8;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
