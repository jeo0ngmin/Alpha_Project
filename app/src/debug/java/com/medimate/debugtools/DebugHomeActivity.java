package com.medimate.debugtools;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.medimate.R;
import com.medimate.net.LocalNetworkPermission;
import com.medimate.pill.PillCaptureActivity;
import com.medimate.pill.PillSearchActivity;
import com.medimate.ui.EdgeToEdgeHelper;

/** [시험용·디버그 전용] 시험 화면 홈. 앱 아이콘 "MediMate 시험"으로 연다. 실제 홈은 팀원(JM)의 MainActivity. */
public class DebugHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_debug_home);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.main));
        LocalNetworkPermission.requestIfNeeded(this);

        findViewById(R.id.btnIdentifyPill).setOnClickListener(
                v -> startActivity(new Intent(this, PillCaptureActivity.class)));
        findViewById(R.id.btnSearchPill).setOnClickListener(
                v -> startActivity(new Intent(this, PillSearchActivity.class)));
        findViewById(R.id.btnBagTest).setOnClickListener(
                v -> startActivity(new Intent(this, BagTestActivity.class)));
        findViewById(R.id.btnPlanTest).setOnClickListener(
                v -> startActivity(new Intent(this, PlanTestActivity.class)));
    }
}
