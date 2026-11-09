package com.medimate.pill.model;

import java.util.Collections;
import java.util.List;

public class PillIdentificationJob {
    public String jobId;
    public PillJobStatus status;
    public PillFeatures extractedFeatures;
    public PillCandidate topMatch;
    public List<PillCandidate> candidates;
    public boolean requiresUserSelection;
    public FallbackAction fallbackAction;
    public String ttsScript;

    public List<PillCandidate> candidatesOrEmpty() {
        return candidates == null ? Collections.emptyList() : candidates;
    }
}
