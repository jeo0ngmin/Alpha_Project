package com.medimate.pill.model;

import java.util.List;

public class SearchResult {
    public List<MedicationSummary> items;
    public PageInfo pageInfo;

    public static class PageInfo {
        public int size;
        public boolean hasNext;
        public String nextCursor;
        public int totalCount;
    }
}
