package com.medimate.pill.model;

import java.util.List;

import com.medimate.plan.model.CurrentMedication;

/** POST /pill-identification 본문. fileId=앞면, backFileId=뒷면(선택). */
public class IdentificationRequest {
    public final String fileId;
    public final String backFileId;
    public final PillHints hints;
    /** 복용 중인 약. 후보의 isInMyMedications 표시에만 쓴다(선택). */
    public final List<CurrentMedication> currentMedications;

    public IdentificationRequest(String fileId, String backFileId, PillHints hints) {
        this(fileId, backFileId, hints, null);
    }

    public IdentificationRequest(String fileId, String backFileId, PillHints hints,
                                 List<CurrentMedication> currentMedications) {
        this.fileId = fileId;
        this.backFileId = backFileId;
        this.hints = hints == null || hints.isEmpty() ? null : hints;
        this.currentMedications = currentMedications == null || currentMedications.isEmpty() ? null : currentMedications;
    }
}
