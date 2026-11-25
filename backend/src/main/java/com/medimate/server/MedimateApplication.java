package com.medimate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 알약 식별 백엔드. 앱의 사진을 받아 pill-classifier 추론 서버에 넘기고 결과를 API 형식으로 돌려준다. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MedimateApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedimateApplication.class, args);
    }
}
