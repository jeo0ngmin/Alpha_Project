package com.medimate.pill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.medimate.net.ApiClient;
import com.medimate.net.ApiException;
import com.medimate.pill.model.ColorClass;
import com.medimate.pill.model.DrugShape;
import com.medimate.pill.model.MedicationSummary;
import com.medimate.pill.model.SearchResult;

/** 모양·색·글자·이름으로 찾기. 커서로 20개씩 이어서 불러온다. */
public class PillSearchViewModel extends ViewModel {

    public static final class State {
        public final List<MedicationSummary> items;
        public final boolean loading;
        public final boolean hasMore;
        public final int totalCount;
        /** 새 검색 결과가 도착했을 때만 채운다 (음성 안내용). */
        public final Integer announcedCount;
        public final ApiException error;

        State(List<MedicationSummary> items, boolean loading, boolean hasMore, int totalCount,
              Integer announcedCount, ApiException error) {
            this.items = items;
            this.loading = loading;
            this.hasMore = hasMore;
            this.totalCount = totalCount;
            this.announcedCount = announcedCount;
            this.error = error;
        }
    }

    private static final int PAGE_SIZE = 20;

    private final PillRepository repository = new PillRepository(ApiClient.get());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(Collections.emptyList(), false, false, 0, null, null));

    private DrugShape shape;
    private List<ColorClass> colors;
    private String printText;
    private String itemName;
    private String nextCursor;

    public LiveData<State> state() {
        return state;
    }

    public void search(DrugShape shape, List<ColorClass> colors, String printText, String itemName) {
        this.shape = shape;
        this.colors = new ArrayList<>(colors);
        this.printText = printText;
        this.itemName = itemName;
        this.nextCursor = null;
        load(Collections.emptyList(), true);
    }

    public void loadMore() {
        State current = state.getValue();
        if (current == null || current.loading || nextCursor == null) {
            return;
        }
        load(current.items, false);
    }

    private void load(List<MedicationSummary> existing, boolean fresh) {
        State current = state.getValue();
        state.setValue(new State(existing, true, false, current == null ? 0 : current.totalCount, null, null));
        String cursor = nextCursor;
        io.execute(() -> {
            try {
                SearchResult r = repository.search(shape, colors, printText, itemName, cursor, PAGE_SIZE);
                List<MedicationSummary> all = new ArrayList<>(existing);
                if (r.items != null) {
                    all.addAll(r.items);
                }
                nextCursor = r.pageInfo != null && r.pageInfo.hasNext ? r.pageInfo.nextCursor : null;
                int total = r.pageInfo == null ? all.size() : r.pageInfo.totalCount;
                state.postValue(new State(all, false, nextCursor != null, total, fresh ? total : null, null));
            } catch (ApiException e) {
                state.postValue(new State(existing, false, nextCursor != null, 0, null, e));
            }
        });
    }

    @Override
    protected void onCleared() {
        io.shutdownNow();
    }
}
