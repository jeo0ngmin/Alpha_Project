package com.medimate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/** ML Kit 한국어 인식기로 구현한 OCR (README 4-0). 기기 안에서 인식하므로 네트워크·키가 필요 없다. */
public class MlKitOcrProcessor implements OcrProcessor {

    private static final String TAG = "MlKitOcr";

    private final TextRecognizer recognizer =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

    @Override
    public void process(Bitmap bitmap, OcrCallback callback) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> callback.onSuccess(toSortedText(text)))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "글자 인식 실패", e);
                    callback.onError("글자를 읽지 못했어요. 다시 시도해 주세요.");
                });
    }

    @Override
    public void close() {
        recognizer.close();
    }

    // 약봉투는 표 형식이 많아 블록 순서가 뒤섞이므로 좌표 기준으로 다시 정렬한다
    private static String toSortedText(Text text) {
        List<TextLineSorter.Item> items = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            // 좌표가 없는 블록은 맨 뒤로 보낸다
            int top = box != null ? box.top : Integer.MAX_VALUE;
            int left = box != null ? box.left : Integer.MAX_VALUE;
            items.add(new TextLineSorter.Item(block.getText(), top, left));
        }
        return TextLineSorter.sort(items);
    }
}
