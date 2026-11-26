package com.medimate;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.data.MyDrugRepository;
import com.medimate.data.MyDrugRepository.MyDrug;
import com.medimate.data.firestore.FirestoreMyDrugRepository;
import com.medimate.drug.model.MedicationInfo;
import com.medimate.model.Drug;
import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.pill.PillRepository;
import com.medimate.tts.TtsManager;
import com.medimate.ui.EdgeToEdgeHelper;
import com.medimate.ui.drug.MedicationDrugMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * [JE 화면] 내 약 (README 9-2, 10-2 D2). 저장한 약 목록을 Firestore(MyDrugRepository)에서 읽는다.
 * 누르면 약 상세(설명·주의사항은 서버의 e약은요), 길게 누르면 내 약에서 지운다.
 */
public class MyMedicineActivity extends AppCompatActivity {

    private static final String MSG_EMPTY = "저장한 약이 없어요. 약 상세 화면에서 내 약에 추가할 수 있어요.";
    private static final String MSG_LOADING = "약 정보를 불러오고 있어요.";
    private static final String MSG_REMOVED = "%s, 내 약에서 지웠어요.";

    private final MyDrugRepository repository = new FirestoreMyDrugRepository();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<MyDrug> drugs = new ArrayList<>();
    private final List<Medicine> medicineList = new ArrayList<>();

    private TtsManager tts;
    private MedicineAdapter adapter;
    private TextView countText;
    private TextView emptyText;
    private boolean opening;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_medicine);
        EdgeToEdgeHelper.padForSystemBars(findViewById(R.id.my_medicine_root));

        tts = new TtsManager(this);
        countText = findViewById(R.id.tv_my_medicine_count);
        emptyText = findViewById(R.id.tv_my_medicine_empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.recycler_my_medicine);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MedicineAdapter(medicineList, medicine -> open(drugs.get(medicineList.indexOf(medicine))));
        adapter.setOnItemLongClickListener(medicine -> confirmRemove(drugs.get(medicineList.indexOf(medicine))));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 약 상세에서 추가하고 돌아오면 바로 보이게 매번 다시 읽는다
        load(drugs.isEmpty());
    }

    private void load(boolean announce) {
        if (!repository.isAvailable()) {
            showMessage(getString(R.string.my_medicine_login_required), announce);
            return;
        }
        repository.list(new MyDrugRepository.Callback<List<MyDrug>>() {
            @Override
            public void onSuccess(List<MyDrug> value) {
                drugs.clear();
                drugs.addAll(value);
                medicineList.clear();
                for (MyDrug drug : value) {
                    medicineList.add(new Medicine(drug.name, drug.dosage));
                }
                adapter.notifyDataSetChanged();
                countText.setText(getString(R.string.my_medicine_count, drugs.size()));
                if (drugs.isEmpty()) {
                    showMessage(MSG_EMPTY, announce);
                } else {
                    emptyText.setVisibility(View.GONE);
                    if (announce) {
                        tts.speak(getString(R.string.my_medicine_spoken, drugs.size()));
                    }
                }
            }

            @Override
            public void onError(String message) {
                showMessage(message, true);
            }
        });
    }

    private void showMessage(String message, boolean speak) {
        emptyText.setText(message);
        emptyText.setVisibility(View.VISIBLE);
        if (speak) {
            tts.speak(message);
        }
    }

    /** 품목기준코드가 있으면 서버에서 e약은요 정보를 받아 상세 화면을 연다 */
    private void open(MyDrug drug) {
        if (opening) {
            return;
        }
        if (drug.itemSeq == null || drug.itemSeq.isEmpty()) {
            startDetail(MedicationDrugMapper.withoutInfo(drug.name, "", drug.dosage, drug.storage));
            return;
        }
        opening = true;
        tts.speak(MSG_LOADING);
        PillRepository pills = new PillRepository(ApiClient.get());
        io.execute(() -> {
            Drug result;
            try {
                MedicationInfo info = pills.getMedication(drug.itemSeq);
                result = MedicationDrugMapper.toDrug(info, drug.dosage, drug.storage);
            } catch (ApiException e) {
                // 서버에 못 가도 저장해 둔 이름·복용법·보관법은 보여 준다
                result = MedicationDrugMapper.withoutInfo(drug.name, drug.itemSeq, drug.dosage, drug.storage);
            }
            Drug detail = result;
            runOnUiThread(() -> {
                opening = false;
                if (!isFinishing()) {
                    startDetail(detail);
                }
            });
        });
    }

    private void startDetail(Drug drug) {
        startActivity(MedicineDetailActivity.newIntent(this, drug, false));
    }

    private void confirmRemove(MyDrug drug) {
        String message = getString(R.string.my_medicine_remove_message, drug.name);
        tts.speak(message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.my_medicine_remove_title)
                .setMessage(message)
                .setPositiveButton(R.string.my_medicine_remove, (d, w) -> remove(drug))
                .setNegativeButton(R.string.schedule_dialog_keep, null)
                .show();
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

    @Override
    protected void onDestroy() {
        tts.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }
}
