package com.medimate.ui.pill;

import static org.junit.Assert.assertEquals;

import com.medimate.pill.model.PillCandidate;
import com.medimate.pill.model.PillIdentificationJob;
import com.medimate.pill.model.PillJobStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PillViewModelPickTest {

    private static PillCandidate candidate(String itemSeq) {
        PillCandidate c = new PillCandidate();
        c.itemSeq = itemSeq;
        c.itemName = "약" + itemSeq;
        return c;
    }

    private static PillIdentificationJob job(boolean requiresSelection, int count) {
        PillIdentificationJob job = new PillIdentificationJob();
        job.status = PillJobStatus.COMPLETED;
        job.requiresUserSelection = requiresSelection;
        job.candidates = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            job.candidates.add(candidate(String.valueOf(i)));
        }
        job.topMatch = count > 0 ? job.candidates.get(0) : null;
        return job;
    }

    @Test
    public void confidentResultShowsOnlyTopMatch() {
        List<PillCandidate> picked = PillViewModel.pick(job(false, 5));
        assertEquals(1, picked.size());
        assertEquals("1", picked.get(0).itemSeq);
    }

    @Test
    public void uncertainResultShowsUpToThreeCandidates() {
        List<PillCandidate> picked = PillViewModel.pick(job(true, 5));
        assertEquals(PillViewModel.MAX_CANDIDATES, picked.size());
        assertEquals("3", picked.get(2).itemSeq);
    }

    @Test
    public void noCandidatesGivesEmptyList() {
        assertEquals(0, PillViewModel.pick(job(true, 0)).size());
    }
}
