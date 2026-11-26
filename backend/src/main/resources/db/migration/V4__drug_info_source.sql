-- 약 설명의 출처: DrbEasyDrugInfoService(e약은요) 또는 DrugPrdtPrmsnInfoService(허가정보, e약은요에 없는 약).
ALTER TABLE drug_info ADD COLUMN source VARCHAR(40);
UPDATE drug_info SET source = 'DrbEasyDrugInfoService' WHERE found = TRUE;
-- 예전에 "e약은요에 없음"으로 기억한 약은 허가정보로 다시 찾을 수 있게 지운다.
DELETE FROM drug_info WHERE found = FALSE;
