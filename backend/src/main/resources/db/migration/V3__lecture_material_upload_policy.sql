-- 강의 자료 첨부의 업로드 정책. 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('lecture_material', '강의 자료 첨부', 52428800, 10, 'pdf,pptx,docx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');
