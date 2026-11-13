-- 학교 e-Class 메인 화면에서 맞춘 과목의 표시 값. 학교 화면은 담당자를 이름 글자(예: "신미현 외 2명")로만 주고 사용자 식별자를 주지
-- 않으므로 course_instructors 행을 만들지 않고 이 글자를 둔다. 비정규 과목의 학습기간도 화면 글자 그대로 둔다(신청 기간이 없어
-- non_regular_offerings 행을 만들 수 없다).
ALTER TABLE courses
  ADD COLUMN instructor_label VARCHAR(200) NULL COMMENT '학교 화면의 담당자 표시(course_instructors 행이 없을 때 쓴다)' AFTER department_name,
  ADD COLUMN learning_period_label VARCHAR(40) NULL COMMENT '학교 화면의 학습기간 표시(예: 2026.05.13~2026.12.21)' AFTER instructor_label;
