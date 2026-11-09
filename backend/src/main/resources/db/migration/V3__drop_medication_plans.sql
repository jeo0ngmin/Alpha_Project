-- 복약 일정은 앱이 Firestore에 저장한다(사용자 데이터는 Firestore 한 곳). 서버는 일정을 보관하지 않으므로 표를 지운다.
-- 서버 DB에는 공용 데이터(식약처 약 설명 캐시, V2)만 남는다.
DROP INDEX IF EXISTS medication_plans_owner;
DROP TABLE IF EXISTS medication_plans;
