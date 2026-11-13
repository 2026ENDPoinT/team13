-- 70 summary: 학생이 학습실에 올린 자기 PDF의 쪽별 요약(docs/adr/0040-study-room-sync.md 14번, docs/adr/0006-summary-module.md).
-- 강의자료 요약과 같은 summary_jobs 원장, pipeline, worker, 출력 검사를 쓴다. 출처가 study_room인 작업은 과목이 없고(course_id NULL)
-- 교수자 동의를 보지 않으며, 요청한 학생만 결과를 읽는다. 게시(summary_publications)는 하지 않는다.
ALTER TABLE summary_jobs
  ADD COLUMN job_origin VARCHAR(12) NOT NULL DEFAULT 'course'
    COMMENT '요청 출처: course(교수자가 요청한 강의자료 요약)|study_room(학생이 요청한 학습실 내 문서 요약)' AFTER course_id,
  ADD CONSTRAINT ck_summary_jobs_origin CHECK (job_origin IN ('course', 'study_room')),
  ADD KEY idx_summary_jobs_origin_requested_created (job_origin, requested_by_user_id, created_at);
