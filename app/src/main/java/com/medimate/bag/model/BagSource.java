package com.medimate.bag.model;

/** 약봉투에 적힌 약국·병원 정보. 읽지 못한 값은 null. */
public class BagSource {
    public String pharmacyName;
    public String hospitalName;
    /** yyyy-MM-dd */
    public String dispensedDate;
    public String patientNameMasked;
}
