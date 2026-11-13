-- 온라인강의 콘텐츠의 출석인정 충족 시각(ADR-0014). 서버 적립 원장으로 출석인정 시간을 처음 채운 순간에 한 번 기록하고,
-- 이후 적립·규칙 변경·권한 변경으로 지우거나 바꾸지 않는다. decided_status만으로는 판정 시각을 남길 수 없다.
ALTER TABLE online_lecture_item_progress
  ADD COLUMN met_at DATETIME(3) NULL COMMENT '출석인정 충족 일시(처음 충족한 서버 시각, 이후 바꾸지 않음)' AFTER decided_status;
