package com.medimate.ui.drug;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.model.Drug;

import java.util.ArrayList;
import java.util.List;

/** 약 목록 화면 (README 1장). 항목을 누르면 상세 화면에서 읽어 준다. */
public class DrugListActivity extends AppCompatActivity {

    private static final String EXTRA_DRUGS = "drugs";

    public static Intent newIntent(Context context, List<Drug> drugs) {
        return new Intent(context, DrugListActivity.class)
                .putExtra(EXTRA_DRUGS, new ArrayList<>(drugs));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_drug_list);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drug_list_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        @SuppressWarnings("unchecked")
        List<Drug> drugs = (List<Drug>) getIntent().getSerializableExtra(EXTRA_DRUGS);
        if (drugs == null) {
            drugs = new ArrayList<>();
        }

        RecyclerView list = findViewById(R.id.drug_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new DrugAdapter(drugs, drug ->
                startActivity(DrugDetailActivity.newIntent(this, drug))));
    }
}
