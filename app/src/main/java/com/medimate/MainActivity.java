package com.medimate;

import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.medimate.publicdata.DrugInfoApi;
import com.medimate.publicdata.PublicDataClient;
import com.medimate.publicdata.model.DrugInfoItem;
import com.medimate.publicdata.model.DrugInfoResponse;
import com.medimate.publicdata.util.HtmlCleaner;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (BuildConfig.DEBUG) {
            logDrugInfoSample();
        }
    }

    // README 7장 2단계 확인용: "타이레놀" e약은요 조회 결과를 Logcat에 출력. 화면 연동(5단계) 때 제거한다.
    private void logDrugInfoSample() {
        DrugInfoApi api = PublicDataClient.get().create(DrugInfoApi.class);
        api.search(BuildConfig.DATA_GO_KR_SERVICE_KEY, "타이레놀", 1, 3, "json")
                .enqueue(new Callback<DrugInfoResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DrugInfoResponse> call,
                                           @NonNull Response<DrugInfoResponse> response) {
                        DrugInfoResponse body = response.body();
                        if (!response.isSuccessful() || body == null) {
                            Log.w(TAG, "e약은요 HTTP " + response.code());
                            return;
                        }
                        if (!body.isSuccess()) {
                            Log.w(TAG, "e약은요 오류: " + body.getHeader().getResultCode()
                                    + " " + body.getHeader().getResultMsg());
                            return;
                        }
                        Log.i(TAG, "e약은요 총 " + body.getBody().getTotalCount() + "건");
                        for (DrugInfoItem item : body.getItems()) {
                            Log.i(TAG, item.getItemSeq() + " | " + item.getItemName() + " | " + item.getEntpName());
                            Log.i(TAG, "  효능: " + HtmlCleaner.clean(item.getEfcyQesitm()));
                            Log.i(TAG, "  보관: " + HtmlCleaner.clean(item.getDepositMethodQesitm()));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<DrugInfoResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "e약은요 호출 실패", t);
                    }
                });
    }
}
