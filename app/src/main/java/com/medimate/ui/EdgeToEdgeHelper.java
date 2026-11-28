package com.medimate.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * targetSdk 35+는 edge-to-edge가 강제되므로
 * 루트 뷰에 시스템 바·키보드만큼 여백을 주고,
 * 밝은 JE 화면에서 상태바 아이콘이 잘 보이도록 한다.
 */
public final class EdgeToEdgeHelper {

    private EdgeToEdgeHelper() {
    }

    public static void padForSystemBars(View root) {

        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (v, insets) -> {

                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.ime()
                    );

                    v.setPadding(
                            left + bars.left,
                            top + bars.top,
                            right + bars.right,
                            bottom + bars.bottom
                    );

                    /*
                     * JE 화면은 밝은 아이보리/흰색 배경을 사용하므로
                     * 상태바와 내비게이션바 아이콘을 어두운 색으로 표시한다.
                     */
                    WindowInsetsControllerCompat controller =
                            ViewCompat.getWindowInsetsController(v);

                    if (controller != null) {
                        controller.setAppearanceLightStatusBars(true);
                        controller.setAppearanceLightNavigationBars(true);
                    }

                    return insets;
                }
        );
    }
}