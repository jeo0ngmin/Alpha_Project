package com.medimate.publicdata;

import com.medimate.publicdata.model.DrugInfoResponse;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/** 의약품개요정보 (e약은요) — README 5-2. */
public interface DrugInfoApi {

    @GET("DrbEasyDrugInfoService/getDrbEasyDrugList")
    Call<DrugInfoResponse> search(
            @Query("serviceKey") String serviceKey,   // Decoding 키 (Retrofit이 인코딩함)
            @Query("itemName") String itemName,
            @Query("pageNo") int pageNo,
            @Query("numOfRows") int numOfRows,
            @Query("type") String type                // "json"
    );
}
