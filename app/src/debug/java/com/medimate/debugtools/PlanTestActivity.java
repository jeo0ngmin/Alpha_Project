package com.medimate.debugtools;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.medimate.ui.EdgeToEdgeHelper;

/**
 * [시험용·디버그 전용] 기기에 저장한 복약 일정 목록(LocalPlans). 알람 켜기·끄기, 종료, 삭제를 해 본다.
 * 서버는 일정을 보관하지 않는다. 실제 앱에서는 팀원의 Firestore 저장소가 이 목록을 가진다.
 */
public class PlanTestActivity extends AppCompatActivity {

    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("내 복약 일정 (시험)");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        EdgeToEdgeHelper.padForSystemBars(scroll);

        root.addView(button("새로 고침", v -> load()));
        root.addView(button("알람 모두 다시 만들기", v -> rescheduleAll()));
        status = text("", 16, Color.DKGRAY, false);
        root.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        JsonArray plans = LocalPlans.all(this);
        list.removeAllViews();
        int n = plans.size();
        status.setText(n == 0 ? "저장된 일정이 없습니다. 약봉투를 확정하면 여기에 생깁니다." : "일정 " + n + "개");
        for (JsonElement e : plans) {
            list.addView(card(e.getAsJsonObject()));
        }
    }

    private View card(JsonObject p) {
        long id = p.get("planId").getAsLong();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        String st = Ui.str(p, "status");
        boolean notify = p.get("notificationEnabled").getAsBoolean();
        card.addView(text("#" + id + " " + Ui.str(p, "itemName"), 19, Color.BLACK, true));
        card.addView(text(Ui.str(p, "dosageText") + " · " + Ui.str(p, "scheduleText"), 16, Color.BLACK, false));
        card.addView(text(Ui.str(p, "startDate") + " ~ " + nz(Ui.str(p, "endDate"))
                + " · " + st + " · 알람 " + (notify ? "켬" : "끔"), 14, Color.DKGRAY, false));
        if (Ui.str(p, "memo") != null) {
            card.addView(text(Ui.str(p, "memo"), 14, Color.rgb(170, 90, 0), false));
        }
        if ("ACTIVE".equals(st)) {
            card.addView(button(notify ? "알람 끄기" : "알람 켜기", v -> setNotify(id, !notify)));
            card.addView(button("복용 종료", v -> delete(id, "STOP")));
        }
        card.addView(button("삭제", v -> delete(id, "PURGE")));
        return card;
    }

    private void setNotify(long id, boolean on) {
        TestAlarms.ensureChannel(this);
        JsonObject plan = LocalPlans.setNotify(this, id, on);
        int n = plan == null ? 0 : TestAlarms.schedule(this, plan); // 끄면 0개가 되며 기존 알람도 지운다
        status.setText(on ? "알람 " + n + "개를 만들었습니다." : "알람을 껐습니다.");
        load();
    }

    private void delete(long id, String mode) {
        if ("PURGE".equals(mode)) {
            LocalPlans.purge(this, id);
        } else {
            LocalPlans.stop(this, id);
        }
        TestAlarms.cancel(this, id);
        load();
    }

    /** 재부팅이나 앱 재설치 뒤처럼, 저장된 일정으로 알람을 모두 다시 만든다. */
    private void rescheduleAll() {
        TestAlarms.ensureChannel(this);
        int total = 0;
        for (JsonElement e : LocalPlans.all(this)) {
            total += TestAlarms.schedule(this, e.getAsJsonObject());
        }
        status.setText("알람 " + total + "개를 다시 만들었습니다.");
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private MaterialButton button(String label, View.OnClickListener onClick) {
        MaterialButton b = new MaterialButton(this);
        b.setText(label);
        b.setTextSize(17);
        b.setMinHeight(dp(56));
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String nz(String s) {
        return s == null ? "끝 없음" : s;
    }
}
