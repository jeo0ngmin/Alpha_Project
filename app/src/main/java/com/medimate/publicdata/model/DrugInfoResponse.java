package com.medimate.publicdata.model;

import java.util.Collections;
import java.util.List;

/** e약은요 응답 래퍼: header / body / items (README 5-2). */
public class DrugInfoResponse {

    /** 정상 응답의 header.resultCode */
    public static final String RESULT_OK = "00";

    private Header header;
    private Body body;

    public Header getHeader() {
        return header;
    }

    public Body getBody() {
        return body;
    }

    public boolean isSuccess() {
        return header != null && RESULT_OK.equals(header.resultCode);
    }

    /** 결과가 없거나 body가 비어 있으면 빈 목록 */
    public List<DrugInfoItem> getItems() {
        if (body == null || body.items == null) {
            return Collections.emptyList();
        }
        return body.items;
    }

    public static class Header {
        private String resultCode;
        private String resultMsg;

        public String getResultCode() {
            return resultCode;
        }

        public String getResultMsg() {
            return resultMsg;
        }
    }

    public static class Body {
        private int pageNo;
        private int totalCount;
        private int numOfRows;
        private List<DrugInfoItem> items;

        public int getPageNo() {
            return pageNo;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public int getNumOfRows() {
            return numOfRows;
        }
    }
}
