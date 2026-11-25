-- e약은요 설명 캐시(30일). found=false는 "e약은요에 없는 약"을 기억해 다시 묻지 않기 위한 것이다.
CREATE TABLE drug_info (
  item_seq       VARCHAR(30) PRIMARY KEY,
  found          BOOLEAN NOT NULL,
  efficacy       TEXT,
  use_method     TEXT,
  warning        TEXT,
  precaution     TEXT,
  interaction    TEXT,
  side_effect    TEXT,
  deposit_method TEXT,
  fetched_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
