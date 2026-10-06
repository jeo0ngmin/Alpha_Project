package com.medimate.publicdata;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.medimate.BuildConfig;
import com.medimate.publicdata.model.DrugInfoItem;
import com.medimate.publicdata.model.DrugInfoResponse;
import com.medimate.publicdata.util.DrugNameNormalizer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 약 이름으로 e약은요를 조회한다 (README 5-4). 1차는 여러 건이면 첫 번째를 쓴다. 캐시는 2차.
 */
public class DrugInfoRepository {

    private static final String TAG = "DrugInfoRepository";

    public interface LookupCallback {
        /** 찾은 항목. 0건이거나 조회에 실패하면 null */
        void onResult(@Nullable DrugInfoItem item);
    }

    private final DrugInfoApi api = PublicDataClient.get().create(DrugInfoApi.class);

    public void lookup(String rawName, LookupCallback callback) {
        String name = DrugNameNormalizer.normalize(rawName);
        if (name.isEmpty()) {
            callback.onResult(null);
            return;
        }
        api.search(BuildConfig.DATA_GO_KR_SERVICE_KEY, name, 1, 1, "json")
                .enqueue(new Callback<DrugInfoResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<DrugInfoResponse> call,
                                           @NonNull Response<DrugInfoResponse> response) {
                        DrugInfoResponse body = response.body();
                        if (!response.isSuccessful() || body == null || !body.isSuccess()) {
                            Log.w(TAG, "e약은요 조회 실패: HTTP " + response.code());
                            callback.onResult(null);
                            return;
                        }
                        // 조회 실패와 0건은 모두 null. 화면은 "공식 정보를 찾지 못했어요"로 표시한다.
                        callback.onResult(body.getItems().isEmpty() ? null : body.getItems().get(0));
                    }

                    @Override
                    public void onFailure(@NonNull Call<DrugInfoResponse> call, @NonNull Throwable t) {
                        Log.e(TAG, "e약은요 호출 실패", t);
                        callback.onResult(null);
                    }
                });
    }
}
