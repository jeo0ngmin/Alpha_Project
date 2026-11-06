package com.medimate;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class ProfileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_profile);

        findViewById(R.id.btn_withdraw)
                .setOnClickListener(v -> {

                    Intent intent = new Intent(
                            ProfileActivity.this,
                            WithdrawActivity.class
                    );

                    startActivity(intent);
                });
    }
}