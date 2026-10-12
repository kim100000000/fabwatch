-- FabWatch 초기 스키마 (V1) — prod 프로파일(ddl-auto=validate)용 Flyway 마이그레이션.
--
-- 기준: JPA 엔티티(Hibernate MySQL 방언이 생성한 DDL)와 1:1 일치. 엔티티를 바꾸면 반드시 V2__*.sql로 변경분을 추가한다.
--       이미 적용된 V1은 절대 수정하지 않는다(Flyway 체크섬 불일치로 기동 실패).
-- 공통 규칙:
--   - 모든 시각 컬럼은 UTC(datetime(6)). 화면 KST 변환은 프론트 책임.
--   - soft delete 대상 테이블은 deleted_at 컬럼을 가진다(물리 삭제 금지, docs/03 F-7).
--   - 도메인 간 참조(alarm.equipment_id 등)는 엔티티가 plain bigint라 FK를 걸지 않는다(모듈러 모놀리스, 서비스 분리 대비).
--     엔티티가 실제 연관관계를 가진 3곳(processes→lines, equipments→processes, equipment_status_logs→equipments)만 FK.
--   - 메모: users.refresh_token* 옛 컬럼은 더 이상 쓰지 않으므로 만들지 않는다. 세션은 refresh_tokens 테이블.

-- ---------------------------------------------------------------------------------------------
-- auth
-- ---------------------------------------------------------------------------------------------

-- TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
CREATE TABLE users (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(100) NOT NULL,
    password           VARCHAR(255) NOT NULL,                       -- BCrypt 해시
    name               VARCHAR(50)  NOT NULL,
    role               ENUM ('ADMIN', 'ENGINEER', 'TECHNICIAN') NOT NULL,
    enabled            BIT(1)       NOT NULL,
    failed_login_count INT          NOT NULL,                       -- 로그인 5회 실패 → 15분 잠금
    locked_until       DATETIME(6)  NULL,
    deleted_at         DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 세션 = 한 행(기기 1대). 토큰 원문은 저장하지 않고 SHA-256 해시만 저장한다.
CREATE TABLE refresh_tokens (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    user_id             BIGINT      NOT NULL,
    token_hash          VARCHAR(64) NOT NULL,
    previous_token_hash VARCHAR(64) NULL,                           -- 회전 직후 유예(병렬 갱신 대비)용 직전 해시
    rotated_at          DATETIME(6) NULL,
    expires_at          DATETIME(6) NOT NULL,
    last_used_at        DATETIME(6) NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    INDEX idx_refresh_tokens_user (user_id),
    INDEX idx_refresh_tokens_expires (expires_at),
    INDEX idx_refresh_tokens_prev (previous_token_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- equipment
-- ---------------------------------------------------------------------------------------------

-- TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
-- 주의: lines 는 MySQL 예약어(LOAD DATA ... LINES)라 항상 백틱으로 감싼다 (JPA 엔티티도 동일하게 인용)
CREATE TABLE `lines` (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE processes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    line_id    BIGINT      NOT NULL,
    name       VARCHAR(50) NOT NULL,
    seq        INT         NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_processes_line (line_id),
    CONSTRAINT fk_processes_line FOREIGN KEY (line_id) REFERENCES `lines` (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
CREATE TABLE equipments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    process_id  BIGINT       NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    model_name  VARCHAR(100) NULL,
    maker       VARCHAR(100) NULL,
    installed_at DATE         NULL,
    status      ENUM ('DOWN', 'IDLE', 'PM', 'RUN') NOT NULL,
    manager_id  BIGINT       NULL,                                  -- users.id (도메인 간 참조라 FK 없음)
    note        VARCHAR(500) NULL,
    deleted_at  DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_equipments_code UNIQUE (code),
    INDEX idx_equipments_process (process_id),
    CONSTRAINT fk_equipments_process FOREIGN KEY (process_id) REFERENCES processes (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 상태 전환 이력 (KPI 가동률/MTBF/MTTR의 원천)
CREATE TABLE equipment_status_logs (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT       NOT NULL,
    from_status  ENUM ('DOWN', 'IDLE', 'PM', 'RUN') NULL,
    to_status    ENUM ('DOWN', 'IDLE', 'PM', 'RUN') NOT NULL,
    reason       VARCHAR(200) NULL,
    changed_by   BIGINT       NULL,                                 -- 시스템 전환이면 NULL
    changed_at   DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_status_log_equipment_changed (equipment_id, changed_at),
    CONSTRAINT fk_status_logs_equipment FOREIGN KEY (equipment_id) REFERENCES equipments (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- sensor
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sensors (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT        NOT NULL,
    type         ENUM ('CURRENT', 'PRESSURE', 'TEMP', 'VIBRATION') NOT NULL,
    unit         VARCHAR(10)   NULL,
    warn_low     DECIMAL(10, 2) NULL,
    warn_high    DECIMAL(10, 2) NULL,
    crit_low     DECIMAL(10, 2) NULL,
    crit_high    DECIMAL(10, 2) NULL,
    base_value   DECIMAL(10, 2) NULL,                               -- 시뮬레이터 정상 기준값
    noise_sigma  DECIMAL(10, 2) NULL,                               -- 시뮬레이터 노이즈 표준편차
    deleted_at   DATETIME(6)   NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sensor_equipment_type UNIQUE (equipment_id, type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 센서 원본 (7일 보관 후 청크 삭제 배치). 단일 measured_at 인덱스는 보관 배치/기간 조회용.
CREATE TABLE sensor_data (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    sensor_id   BIGINT         NOT NULL,
    value       DECIMAL(10, 2) NOT NULL,
    measured_at DATETIME(6)    NOT NULL,
    created_at  DATETIME(6)    NOT NULL,
    updated_at  DATETIME(6)    NULL,
    PRIMARY KEY (id),
    INDEX idx_sensor_data_sensor_measured (sensor_id, measured_at),
    INDEX idx_sensor_data_measured (measured_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 1분 집계 (영구 보관)
CREATE TABLE sensor_data_1m (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    sensor_id    BIGINT         NOT NULL,
    bucket_at    DATETIME(6)    NOT NULL,
    avg_v        DECIMAL(10, 2) NULL,
    min_v        DECIMAL(10, 2) NULL,
    max_v        DECIMAL(10, 2) NULL,
    sample_count INT            NULL,
    created_at   DATETIME(6)    NOT NULL,
    updated_at   DATETIME(6)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sensor_data_1m_sensor_bucket UNIQUE (sensor_id, bucket_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 임계치 변경 감사 이력 (누가/언제/왜)
CREATE TABLE sensor_threshold_logs (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    sensor_id     BIGINT         NOT NULL,
    equipment_id  BIGINT         NOT NULL,
    old_warn_low  DECIMAL(10, 2) NULL,
    old_warn_high DECIMAL(10, 2) NULL,
    old_crit_low  DECIMAL(10, 2) NULL,
    old_crit_high DECIMAL(10, 2) NULL,
    new_warn_low  DECIMAL(10, 2) NULL,
    new_warn_high DECIMAL(10, 2) NULL,
    new_crit_low  DECIMAL(10, 2) NULL,
    new_crit_high DECIMAL(10, 2) NULL,
    reason        VARCHAR(300)   NOT NULL,
    changed_by    BIGINT         NULL,
    changed_at    DATETIME(6)    NOT NULL,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NULL,
    PRIMARY KEY (id),
    INDEX idx_sensor_threshold_log_sensor (sensor_id, changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- simulator
-- ---------------------------------------------------------------------------------------------

CREATE TABLE simulation_scenarios (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    sensor_id  BIGINT        NOT NULL,
    type       ENUM ('DRIFT', 'SPIKE', 'STEP') NOT NULL,
    param      VARCHAR(1000) NULL,                                  -- 유형별 JSON 파라미터
    active     BIT(1)        NOT NULL,
    started_at DATETIME(6)   NULL,
    ended_at   DATETIME(6)   NULL,
    deleted_at DATETIME(6)   NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    INDEX idx_scenario_sensor (sensor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- alarm
-- ---------------------------------------------------------------------------------------------

CREATE TABLE alarms (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    equipment_id    BIGINT         NOT NULL,
    sensor_id       BIGINT         NULL,
    alarm_type      ENUM ('MANUAL', 'PM_OVERDUE', 'SENSOR_THRESHOLD') NOT NULL,
    severity        ENUM ('CRITICAL', 'MAJOR', 'WARNING') NOT NULL,
    message         VARCHAR(300)   NOT NULL,
    trigger_value   DECIMAL(10, 2) NULL,
    threshold_value DECIMAL(10, 2) NULL,
    status          ENUM ('ACK', 'OPEN', 'RESOLVED') NOT NULL,
    occurred_at     DATETIME(6)    NOT NULL,
    ack_by          BIGINT         NULL,
    ack_at          DATETIME(6)    NULL,
    resolved_by     BIGINT         NULL,
    resolved_at     DATETIME(6)    NULL,
    resolve_note    VARCHAR(300)   NULL,
    deleted_at      DATETIME(6)    NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NULL,
    PRIMARY KEY (id),
    INDEX idx_alarm_equipment_status (equipment_id, status),
    INDEX idx_alarm_status_occurred (status, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- inspection
-- ---------------------------------------------------------------------------------------------

CREATE TABLE checklist_items (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT       NOT NULL,
    item_name    VARCHAR(200) NOT NULL,
    criteria     VARCHAR(200) NULL,
    seq          INT          NULL,
    active       BIT(1)       NOT NULL,
    deleted_at   DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_checklist_item_equipment (equipment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inspections (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id BIGINT       NOT NULL,
    worker_id    BIGINT       NOT NULL,
    type         ENUM ('BM', 'PM') NOT NULL,
    shift        ENUM ('D', 'N') NOT NULL,                          -- KST 기준 교대 (D=08~20시, N=20~08시)
    started_at   DATETIME(6)  NOT NULL,
    ended_at     DATETIME(6)  NOT NULL,
    duration_min INT          NULL,
    content      TEXT         NOT NULL,
    action_taken TEXT         NULL,
    cause_4m     ENUM ('MACHINE', 'MAN', 'MATERIAL', 'METHOD') NULL,
    cause_detail VARCHAR(300) NULL,
    has_ng       BIT(1)       NOT NULL,
    alarm_id     BIGINT       NULL,
    reviewed_by  BIGINT       NULL,
    reviewed_at  DATETIME(6)  NULL,
    deleted_at   DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_inspection_equipment_started (equipment_id, started_at),
    INDEX idx_inspection_type_shift (type, shift)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inspection_check_results (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    inspection_id     BIGINT       NOT NULL,
    checklist_item_id BIGINT       NOT NULL,
    result            ENUM ('NA', 'NG', 'OK') NOT NULL,
    note              VARCHAR(300) NULL,
    deleted_at        DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_check_result_inspection (inspection_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE pm_schedules (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    equipment_id       BIGINT      NOT NULL,
    cycle_type         ENUM ('DAILY', 'MONTHLY', 'WEEKLY') NOT NULL,
    cycle_value        INT         NULL,
    last_done_at       DATETIME(6) NULL,
    next_due_at        DATETIME(6) NOT NULL,
    overdue_alarm_sent BIT(1)      NOT NULL,                        -- PM 초과 알람 중복 발송 방지
    deleted_at         DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pm_schedules_equipment UNIQUE (equipment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------------------------
-- aireport
-- ---------------------------------------------------------------------------------------------

CREATE TABLE ai_reports (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    equipment_id      BIGINT       NOT NULL,
    alarm_id          BIGINT       NULL,
    inspection_id     BIGINT       NULL,
    title             VARCHAR(200) NOT NULL,
    status            ENUM ('CONFIRMED', 'DRAFT', 'FAILED', 'GENERATING') NOT NULL,
    draft_content     MEDIUMTEXT   NULL,                            -- AI 초안 (원문 보존)
    final_content     MEDIUMTEXT   NULL,                            -- 사람이 편집·확정한 본문
    fail_reason       VARCHAR(300) NULL,                            -- 정제된 고정 문구만 (API 키·응답 원문 저장 금지)
    model             VARCHAR(50)  NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    created_by        BIGINT       NULL,
    confirmed_by      BIGINT       NULL,
    confirmed_at      DATETIME(6)  NULL,
    deleted_at        DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_report_equipment_created (equipment_id, created_at),
    INDEX idx_ai_report_status_updated (status, updated_at),
    INDEX idx_ai_report_alarm (alarm_id),
    INDEX idx_ai_report_inspection (inspection_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 일일 호출 쿼터 집계 + 호출 감사 로그 (키·응답 원문 저장 금지)
CREATE TABLE ai_call_logs (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    report_id         BIGINT       NULL,
    user_id           BIGINT       NULL,
    provider          VARCHAR(10)  NOT NULL,                        -- claude | mock
    model             VARCHAR(50)  NULL,
    success           BIT(1)       NULL,
    error_summary     VARCHAR(300) NULL,
    prompt_tokens     INT          NULL,
    completion_tokens INT          NULL,
    requested_at      DATETIME(6)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_call_requested (requested_at),
    INDEX idx_ai_call_report (report_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
