-- 40 assessment: 성적부(docs/adr/0023-gradebook-ownership-release-totals.md)
-- 성적 설정, 성적 항목, 학생 성적의 동시 수정 감지(lost update 방지). 수정 요청은 읽은 번호를 보내고, 다르면 409다.

ALTER TABLE grade_settings
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '성적 설정 수정 번호(수정할 때마다 1 증가, 동시 수정 감지)';

ALTER TABLE grade_items
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '성적 항목 수정 번호(이름·배점·비율·공개를 바꿀 때마다 1 증가, 동시 수정 감지)';

ALTER TABLE grade_scores
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '학생 성적 수정 번호(점수·의견을 쓸 때마다 1 증가, 동시 수정 감지)';
