package com.medimate.ui;

import java.util.Locale;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

/**
 * 음성 안내. TalkBack이 켜져 있으면 TTS를 쓰지 않고 live region 텍스트로 알려서 두 목소리가 겹치지 않게 한다.
 * TODO(설정): /users/me/accessibility의 ttsEnabled·ttsSpeechRate·ttsPitch를 반영.
 */
public final class Speaker implements TextToSpeech.OnInitListener {

    private final AccessibilityManager accessibility;
    private TextToSpeech tts;
    private boolean ready;
    private String pending;

    public Speaker(Context context) {
        accessibility = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            return;
        }
        int lang = tts.setLanguage(Locale.KOREAN);
        ready = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED;
        if (ready && pending != null) {
            speakNow(pending);
            pending = null;
        }
    }

    /**
     * liveRegion에 문구를 표시하고 읽어 준다. liveRegion은 레이아웃에서
     * android:accessibilityLiveRegion="polite"로 선언된 TextView여야 한다.
     */
    public void say(TextView liveRegion, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        liveRegion.setText(text);
        if (isTalkBackOn()) {
            return; // TalkBack이 live region 변경을 읽는다
        }
        if (ready) {
            speakNow(text);
        } else {
            pending = text;
        }
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    private boolean isTalkBackOn() {
        return accessibility != null && accessibility.isEnabled() && accessibility.isTouchExplorationEnabled();
    }

    private void speakNow(String text) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "medimate-" + text.hashCode());
    }
}
