package com.medimate.ui.drug;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.data.MyDrugRepository;
import com.medimate.data.MyDrugRepository.MyDrug;
import com.medimate.data.firestore.FirestoreMyDrugRepository;
import com.medimate.tts.TtsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 내 약 목록 (README 10-2 D2). 테스트용 임시 화면 — JE 화면이 생기면 교체한다.
 * 저장은 MyDrugRepository.add (약 상세 화면에 "내 약에 추가" 버튼은 아직 없다).
 * 항목을 누르면 이름·복용법·보관법을 읽어 주고, 지우기 버튼으로 지운다.
 */
public class MyDrugsActivity extends AppCompatActivity {

    private static final String MSG_EMPTY = "저장한 약이 없어요.";
    private static final String MSG_REMOVED = "%s, 내 약에서 지웠어요.";

    private final MyDrugRepository repository = new FirestoreMyDrugRepository();
    private final List<MyDrug> drugs = new ArrayList<>();
    private TtsManager tts;
    private Adapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_my_drugs);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.my_drugs_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int gutter = (int) (16 * getResources().getDisplayMetrics().density);
            v.setPadding(systemBars.left + gutter, systemBars.top, systemBars.right + gutter, systemBars.bottom);
            return insets;
        });

        tts = new TtsManager(this);
        empty = findViewById(R.id.text_empty);
        RecyclerView list = findViewById(R.id.list_my_drugs);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        load(savedInstanceState == null);
    }

    private void load(boolean announce) {
        repository.list(new MyDrugRepository.Callback<List<MyDrug>>() {
            @Override
            public void onSuccess(List<MyDrug> value) {
                drugs.clear();
                drugs.addAll(value);
                adapter.notifyDataSetChanged();
                empty.setVisibility(drugs.isEmpty() ? View.VISIBLE : View.GONE);
                if (announce) {
                    tts.speak(drugs.isEmpty() ? MSG_EMPTY : "내 약이 " + drugs.size() + "개 있어요.");
                }
            }

            @Override
            public void onError(String message) {
                empty.setText(message);
                empty.setVisibility(View.VISIBLE);
                tts.speak(message);
            }
        });
    }

    private void remove(MyDrug drug) {
        repository.remove(drug.id, new MyDrugRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void value) {
                tts.speak(String.format(MSG_REMOVED, drug.name));
                load(false);
            }

            @Override
            public void onError(String message) {
                tts.speak(message);
            }
        });
    }

    private String sentence(MyDrug drug) {
        StringBuilder sb = new StringBuilder(drug.name).append(". ");
        if (!drug.dosage.isEmpty()) {
            sb.append("복용법").append(". ").append(drug.dosage).append(". ");
        }
        if (!drug.storage.isEmpty()) {
            sb.append("보관법").append(". ").append(drug.storage).append(". ");
        }
        return sb.toString();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_my_drug, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MyDrug drug = drugs.get(position);
            holder.name.setText(drug.name);
            holder.dosage.setText(drug.dosage);
            holder.dosage.setVisibility(drug.dosage.isEmpty() ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> tts.speak(sentence(drug)));
            holder.remove.setContentDescription(getString(R.string.my_drugs_remove, drug.name));
            holder.remove.setOnClickListener(v -> remove(drug));
        }

        @Override
        public int getItemCount() {
            return drugs.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView dosage;
        final ImageButton remove;

        Holder(View view) {
            super(view);
            name = view.findViewById(R.id.text_name);
            dosage = view.findViewById(R.id.text_dosage);
            remove = view.findViewById(R.id.button_remove);
        }
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }
}
