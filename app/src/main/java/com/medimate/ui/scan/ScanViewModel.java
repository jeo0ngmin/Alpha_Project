package com.medimate.ui.scan;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.medimate.gpt.GptCallback;
import com.medimate.gpt.GptProcessor;
import com.medimate.gpt.model.GptDrugList;
import com.medimate.model.Drug;
import com.medimate.ocr.MlKitOcrProcessor;
import com.medimate.ocr.OcrCallback;
import com.medimate.ocr.OcrProcessor;
import com.medimate.publicdata.DrugInfoRepository;
import com.medimate.publicdata.model.DrugInfoItem;
import com.medimate.publicdata.util.HtmlCleaner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 사진 → OCR → GPT 정리 → e약은요 조회 (README 4-4). OCR 구현은 인터페이스로만 참조한다.
 * 여러 건이면 첫 번째를 쓴다. 후보 제시·음성 확인은 2차.
 */
public class ScanViewModel extends ViewModel {

    static final String MSG_READING = "글자를 읽고 있어요";
    static final String MSG_SEARCHING = "약 정보를 찾고 있어요";
    static final String MSG_NO_TEXT = "글자를 찾지 못했어요. 약봉투를 화면 가운데에 두고 다시 찍어 주세요.";
    static final String MSG_NO_DRUG = "약 이름을 찾지 못했어요. 약봉투를 다시 찍어 주세요.";

    private final OcrProcessor ocr = new MlKitOcrProcessor();   // 나중에: new ClovaOcrProcessor()
    private final GptProcessor gpt = new GptProcessor();
    private final DrugInfoRepository repository = new DrugInfoRepository();

    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<String> status = new MutableLiveData<>();
    private final MutableLiveData<List<Drug>> drugs = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();

    public LiveData<Boolean> getIsLoading() {
        return isLoading;
    }

    /** 진행 안내 ("글자를 읽고 있어요" → "약 정보를 찾고 있어요"). 화면에 보여 주고 읽어 준다 */
    public LiveData<String> getStatus() {
        return status;
    }

    /** 완성된 약 목록. 화면이 받아 가면 consumeDrugs()로 비운다 */
    public LiveData<List<Drug>> getDrugs() {
        return drugs;
    }

    /** 사용자에게 그대로 읽어 줄 실패 안내 */
    public LiveData<String> getError() {
        return error;
    }

    public void start(Bitmap bitmap) {
        isLoading.setValue(true);
        status.setValue(MSG_READING);
        ocr.process(bitmap, new OcrCallback() {
            @Override
            public void onSuccess(String text) {
                if (text.isEmpty()) {
                    // 빈 결과는 GPT를 부르지 않고 바로 다시 찍도록 안내한다 (README 4-0)
                    fail(MSG_NO_TEXT);
                    return;
                }
                status.setValue(MSG_SEARCHING);
                structure(text);
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });
    }

    public void consumeDrugs() {
        drugs.setValue(null);
    }

    private void structure(String ocrText) {
        gpt.process(ocrText, new GptCallback() {
            @Override
            public void onSuccess(GptDrugList result) {
                if (result.getDrugs().isEmpty()) {
                    fail(MSG_NO_DRUG);
                    return;
                }
                lookupAll(result.getDrugs());
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });
    }

    // 약마다 e약은요를 따로 조회하고, 모두 끝나면 원래 순서대로 목록을 만든다
    private void lookupAll(List<GptDrugList.GptDrug> gptDrugs) {
        Drug[] results = new Drug[gptDrugs.size()];
        int[] remaining = {gptDrugs.size()};
        for (int i = 0; i < gptDrugs.size(); i++) {
            int index = i;
            GptDrugList.GptDrug gptDrug = gptDrugs.get(i);
            repository.lookup(gptDrug.getName(), item -> {
                results[index] = toDrug(gptDrug, item);
                if (--remaining[0] == 0) {
                    isLoading.setValue(false);
                    drugs.setValue(new ArrayList<>(Arrays.asList(results)));
                }
            });
        }
    }

    /** 이름·용법은 OCR 원문(GPT), 설명·주의사항·보관법은 e약은요 (README 6-3) */
    private static Drug toDrug(GptDrugList.GptDrug gptDrug, @Nullable DrugInfoItem item) {
        if (item == null) {
            return new Drug(gptDrug.getName(), "", "", gptDrug.getDosage(), gptDrug.getStorage(),
                    Drug.NO_OFFICIAL_INFO, "");
        }
        String storage = gptDrug.getStorage().isEmpty()
                ? HtmlCleaner.clean(item.getDepositMethodQesitm()) : gptDrug.getStorage();
        String warning = (HtmlCleaner.clean(item.getAtpnWarnQesitm()) + "\n"
                + HtmlCleaner.clean(item.getAtpnQesitm())).trim();
        return new Drug(item.getItemName(), item.getEntpName(), item.getItemSeq(),
                gptDrug.getDosage(), storage, HtmlCleaner.clean(item.getEfcyQesitm()), warning);
    }

    private void fail(String message) {
        isLoading.setValue(false);
        error.setValue(message);
    }

    @Override
    protected void onCleared() {
        ocr.close();
    }
}
