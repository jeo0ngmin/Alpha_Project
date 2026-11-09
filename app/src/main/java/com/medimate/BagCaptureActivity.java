package com.medimate;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** [JE 화면] 약봉투 촬영. 원래 이름은 MainActivity였다(홈과 이름이 겹쳐서 바꿈). 기능은 아직 연결 안 됨. */
public class BagCaptureActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_bag_capture);
    }
}
