package com.medimate.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/** 한국어 음성 낭독. 화면을 못 보는 사용자에게는 실패·진행 안내 음성이 곧 UI다 (README 4-4). */
public class TtsManager {

    private static final String TAG = "Tts";

    private final TextToSpeech tts;
    private boolean ready = false;
    // 초기화가 끝나기 전에 들어온 문장은 하나만 기억했다가 읽는다
    private String pending;

    public TtsManager(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this::onInit);
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 초기화 실패: " + status);
            return;
        }
        int result = tts.setLanguage(Locale.KOREAN);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "한국어 TTS를 지원하지 않음: " + result);
            return;
        }
        ready = true;
        if (pending != null) {
            speak(pending);
            pending = null;
        }
    }

    /** 읽고 있던 문장을 끊고 새 문장을 읽는다. */
    public void speak(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!ready) {
            pending = text;
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "medimate");
    }

    public void shutdown() {
        tts.stop();
        tts.shutdown();
    }
}
