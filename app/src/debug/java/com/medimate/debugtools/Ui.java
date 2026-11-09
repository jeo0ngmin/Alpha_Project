package com.medimate.debugtools;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import androidx.core.view.ViewCompat;
import com.medimate.ui.EdgeToEdgeHelper;

import java.util.ArrayList;
import java.util.List;

/** [시험용·디버그 전용] 시험 화면 공용 도우미. 팀 앱처럼 위쪽에 색 띠 머리글과 뒤로 가기 버튼을 둔다. */
final class Ui {

    static final int BRAND = Color.rgb(84, 104, 196);
    static final int WARN = Color.rgb(170, 90, 0);
    static final int ALERT = Color.rgb(190, 0, 0);

    private Ui() {
    }

    /** 머리글(뒤로 가기 + 제목)과 스크롤되는 본문. 본문 LinearLayout을 돌려준다. */
    static LinearLayout page(Activity a, String title) {
        LinearLayout root = column(a);
        root.setBackgroundColor(Color.rgb(248, 248, 252));

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(BRAND);
        header.setPadding(dp(a, 8), dp(a, 8), dp(a, 16), dp(a, 8));
        MaterialButton back = new MaterialButton(a, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        back.setText("← 뒤로");
        back.setTextColor(Color.WHITE);
        back.setTextSize(17);
        back.setContentDescription("뒤로 가기");
        back.setOnClickListener(v -> a.finish());
        header.addView(back);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(t, true); // API 28 미만(안드로이드 8)에서도 동작
        t.setPadding(dp(a, 8), 0, 0, 0);
        header.addView(t);
        root.addView(header);

        ScrollView scroll = new ScrollView(a);
        LinearLayout body = column(a);
        body.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        a.setContentView(root);
        EdgeToEdgeHelper.padForSystemBars(root);
        return body;
    }

    static LinearLayout column(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static TextView text(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(a, 4), 0, dp(a, 4));
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    static MaterialButton button(Activity a, String label, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(a);
        b.setText(label);
        b.setTextSize(17);
        b.setMinHeight(dp(a, 56));
        b.setOnClickListener(onClick);
        return b;
    }

    static MaterialButton outlined(Activity a, String label, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setTextSize(17);
        b.setMinHeight(dp(a, 56));
        b.setOnClickListener(onClick);
        return b;
    }

    /** 흰 카드. 안쪽 LinearLayout을 돌려준다. */
    static LinearLayout card(Activity a, LinearLayout parent, View.OnClickListener onClick) {
        MaterialCardView card = new MaterialCardView(a);
        card.setRadius(dp(a, 16));
        card.setCardElevation(dp(a, 2));
        card.setCardBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 12);
        card.setLayoutParams(lp);
        if (onClick != null) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(onClick);
        }
        LinearLayout inner = column(a);
        inner.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16));
        card.addView(inner);
        parent.addView(card);
        return inner;
    }

    /** 작은 둥근 표시(예: "확인 필요"). */
    static TextView badge(Activity a, String s, int color) {
        TextView t = text(a, s, 14, Color.WHITE, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 12));
        bg.setColor(color);
        t.setBackground(bg);
        t.setPadding(dp(a, 10), dp(a, 2), dp(a, 10), dp(a, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 4);
        t.setLayoutParams(lp);
        return t;
    }

    static void tint(MaterialButton b, int color) {
        b.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    static String join(JsonArray a, String sep) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return String.join(sep, out);
    }

    /** 약 이름: 식약처 품목명이 있으면 괄호 속 성분을 빼고, 없으면 봉투에서 읽은 이름. */
    static String drugName(JsonObject it) {
        String name = str(it, "itemName") != null ? str(it, "itemName") : str(it, "refinedName");
        if (name == null) {
            name = str(it, "rawText");
        }
        return name == null ? "이름 없음" : name.replaceAll("\\([^)]*\\)", "").trim();
    }
}
