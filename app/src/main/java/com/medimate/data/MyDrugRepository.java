package com.medimate.data;

import java.util.List;

/**
 * 내 약 저장소 (README 9-2, 9-4, 10-2 D2). 화면은 Firestore 대신 이 인터페이스만 참조한다.
 * 약 효능·주의사항 본문은 저장하지 않는다. 이름·용법·보관법과 품목기준코드(itemSeq)만 둔다.
 * (복약 일정은 data/medication/MedicationRepository가 따로 맡는다, README 9-6)
 */
public interface MyDrugRepository {

    String SOURCE_OCR = "ocr";
    String SOURCE_MANUAL = "manual";

    /** 저장된 약 한 개 */
    final class MyDrug {
        public final String id;
        public final String itemSeq;
        public final String name;
        public final String dosage;
        public final String storage;

        public MyDrug(String id, String itemSeq, String name, String dosage, String storage) {
            this.id = id;
            this.itemSeq = itemSeq;
            this.name = name;
            this.dosage = dosage;
            this.storage = storage;
        }
    }

    interface Callback<T> {
        /** 메인 스레드에서 불린다 */
        void onSuccess(T value);

        /** 메인 스레드에서 불린다. message는 사용자에게 읽어 줄 문장 */
        void onError(String message);
    }

    /** Firebase 로그인 사용자만 쓸 수 있다 (카카오는 아직 안 됨, README 7-5) */
    boolean isAvailable();

    /** 같은 약(itemSeq, 없으면 이름)이 이미 있으면 새로 만들지 않고 용법·보관법만 갱신한다 */
    void add(String itemSeq, String name, String dosage, String storage, String source, Callback<Void> callback);

    /** 추가한 순서대로 */
    void list(Callback<List<MyDrug>> callback);

    void remove(String id, Callback<Void> callback);
}
