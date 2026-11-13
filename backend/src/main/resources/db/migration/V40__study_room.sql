-- 60 files: 학습실의 내 폴더, 항목 배치, 강의 녹음을 서버에 둔다(docs/adr/0040-study-room-sync.md).
-- 모두 소유자 한 사람의 것이다. 담당자, 운영자, 요약·학습 대화는 읽지 않는다. 한 사용자의 학습실 변경(폴더 만들기·이름 바꾸기·
-- 지우기·순서 바꾸기, 배치, 녹음 올리기·이름 바꾸기·지우기)은 study_room_quotas의 그 사용자 행을 먼저 잠그고 차례로 처리한다.
-- 잠금 순서는 study_room_quotas → study_room_folders → study_room_recordings → study_room_placements → file_blobs → files →
-- upload_sessions다.

-- 강의 녹음 업로드 정책. 녹음 파일은 과목 없이(files.course_id NULL) 저장하고 첨부하지 않으므로 과목 파일 경로로는 열리지 않는다.
-- 확장자마다 내용 형식을 함께 검사한다(files.upload.ContentInspector). 크기 상한은 악성코드 검사기가 받는 크기(64 MB) 아래로 둔다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('study_recording', '학습실 강의 녹음', 52428800, 1, 'webm,ogg,mp3,m4a,mp4,wav,aac,flac');

-- 사용자별 학습실 사용량. 용량·개수 검사와 증감을 이 행을 잠근 뒤 같은 transaction에서 하므로 동시에 올려도 한도를 넘지 않는다.
CREATE TABLE study_room_quotas (
  owner_user_id   BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  used_bytes      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '녹음 파일이 차지하는 바이트',
  recording_count INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '녹음 수',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at      DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (owner_user_id),
  CONSTRAINT fk_study_room_quotas_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 사용량';

-- 과목 학습실 안의 내 폴더. 한 단계뿐이다(폴더 안의 폴더는 없다). 같은 과목 안 이름은 대소문자 구분 없이 유일하다.
CREATE TABLE study_room_folders (
  study_room_folder_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습실 폴더 ID',
  owner_user_id        BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  course_id            BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  folder_name          VARCHAR(60)     NOT NULL COMMENT '폴더 이름',
  folder_position      INT UNSIGNED    NOT NULL COMMENT '과목 안 표시 순서(1부터)',
  folder_version       INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '이름 바꾸기·순서 바꾸기·지우기 낙관적 버전',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (study_room_folder_id),
  UNIQUE KEY uq_study_room_folders_owner_course_name (owner_user_id, course_id, folder_name),
  KEY idx_study_room_folders_course (course_id),
  CONSTRAINT fk_study_room_folders_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_room_folders_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 폴더';

-- 학생이 녹음했거나 가져온 강의 오디오. 바이트는 files와 file_blobs(content bucket)에 있고, 이 행은 표시 정보와 과목을 둔다.
CREATE TABLE study_room_recordings (
  study_room_recording_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습실 녹음 ID',
  owner_user_id           BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  course_id               BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  file_id                 BIGINT UNSIGNED NOT NULL COMMENT '오디오 파일 ID',
  recording_title         VARCHAR(200)    NOT NULL COMMENT '녹음 제목',
  recording_source        VARCHAR(8)      NOT NULL COMMENT '녹음 출처: mic|tab|audio',
  audio_mime_type         VARCHAR(40)     NOT NULL COMMENT '재생 MIME(내용으로 판정한 형식의 audio 형식)',
  duration_ms             INT UNSIGNED    NOT NULL COMMENT '재생 길이(밀리초, 브라우저가 잰 값)',
  recorded_at             DATETIME(3)     NOT NULL COMMENT '녹음 시작 일시(브라우저가 보낸 값, 가져온 파일은 가져온 일시)',
  recording_version       INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '이름 바꾸기·지우기 낙관적 버전',
  created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at              DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (study_room_recording_id),
  UNIQUE KEY uq_study_room_recordings_file (file_id),
  KEY idx_study_room_recordings_owner_course (owner_user_id, course_id, recorded_at),
  KEY idx_study_room_recordings_course (course_id),
  CONSTRAINT ck_study_room_recordings_source CHECK (recording_source IN ('mic', 'tab', 'audio')),
  CONSTRAINT fk_study_room_recordings_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_room_recordings_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_study_room_recordings_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 녹음';

-- 항목을 폴더에 둔 배치. 행이 없는 항목은 과목 학습실 맨 위에 있다. 항목은 과목 강의자료 파일이나 내 녹음 가운데 하나이고 폴더 하나에만 있다.
CREATE TABLE study_room_placements (
  study_room_placement_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습실 배치 ID',
  owner_user_id           BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  study_room_folder_id    BIGINT UNSIGNED NOT NULL COMMENT '둔 폴더 ID',
  file_id                 BIGINT UNSIGNED NULL     COMMENT '과목 강의자료 파일 ID(녹음이면 NULL)',
  study_room_recording_id BIGINT UNSIGNED NULL     COMMENT '녹음 ID(강의자료 파일이면 NULL)',
  placement_version       INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '옮기기·빼기 낙관적 버전',
  created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at              DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (study_room_placement_id),
  UNIQUE KEY uq_study_room_placements_owner_file (owner_user_id, file_id),
  UNIQUE KEY uq_study_room_placements_recording (study_room_recording_id),
  KEY idx_study_room_placements_folder (study_room_folder_id),
  KEY idx_study_room_placements_file (file_id),
  CONSTRAINT ck_study_room_placements_item CHECK ((file_id IS NULL) <> (study_room_recording_id IS NULL)),
  CONSTRAINT fk_study_room_placements_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_room_placements_study_room_folders FOREIGN KEY (study_room_folder_id) REFERENCES study_room_folders (study_room_folder_id),
  CONSTRAINT fk_study_room_placements_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_study_room_placements_study_room_recordings FOREIGN KEY (study_room_recording_id) REFERENCES study_room_recordings (study_room_recording_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 배치';
