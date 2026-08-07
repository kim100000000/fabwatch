# 05. DB 설계서 (ERD) — FabWatch

> 버전 v1.0 / 2026-07-06 / MySQL 8.x, JPA(Hibernate)
> 공통: 모든 테이블 `id BIGINT PK AUTO_INCREMENT`, `created_at`, `updated_at`, soft delete 대상은 `deleted_at`.
> `// TENANT:` 표시 = 수익화(멀티테넌트) 전환 시 `tenant_id` 추가 지점.

## 1. ERD 개요

```
users ─┬─< inspections >─┬─ equipments >─ processes >─ lines
       │                 └─< inspection_check_results >─ checklist_items
       ├─< alarms (ack_by, resolved_by)
       └─< ai_reports (created_by)

equipments ─< sensors ─< sensor_data (원본, 7일)
                      └─< sensor_data_1m (1분 집계, 영구)
equipments ─< equipment_status_logs
equipments ─< pm_schedules
alarms ─── ai_reports (nullable 연계)
inspections ─── alarms (BM-알람 연계, nullable)
```

## 2. 테이블 정의

### users // TENANT:
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| email | VARCHAR(100) | UNIQUE, NN | 로그인 ID |
| password | VARCHAR(255) | NN | BCrypt |
| name | VARCHAR(50) | NN | |
| role | ENUM('ADMIN','ENGINEER','TECHNICIAN') | NN | |
| enabled | BOOLEAN | DEFAULT TRUE | 비활성화 |
| refresh_token | VARCHAR(512) | NULL | 로그아웃 시 NULL |

### lines / processes
| 테이블 | 컬럼 |
|---|---|
| lines | name VARCHAR(50) NN (예: CELL-1라인) // TENANT: |
| processes | line_id FK NN, name VARCHAR(50) NN (예: 합착), seq INT (표시 순서) |

### equipments // TENANT:
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| process_id | FK | NN | |
| code | VARCHAR(30) | UNIQUE, NN | LAMI-01 |
| name | VARCHAR(100) | NN | 합착기 1호기 |
| model_name / maker | VARCHAR(100) | NULL | |
| installed_at | DATE | NULL | |
| status | ENUM('RUN','IDLE','DOWN','PM') | NN, DEFAULT 'IDLE' | |
| manager_id | FK users | NULL | 담당 엔지니어 |

### equipment_status_logs  ← KPI 계산 원천
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN | |
| from_status / to_status | ENUM | |
| reason | VARCHAR(200) | 예: "CRITICAL 알람 자동 DOWN (ALM-123)" |
| changed_by | FK users NULL | NULL = 시스템 자동 |
| changed_at | DATETIME NN | INDEX (equipment_id, changed_at) |

### sensors
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN | |
| type | ENUM('TEMP','VIBRATION','PRESSURE','CURRENT') | |
| unit | VARCHAR(10) | ℃, mm/s, kPa, A |
| base_value / noise_sigma | DECIMAL(10,2) | 시뮬레이터 기준값 |
| warn_low/warn_high/crit_low/crit_high | DECIMAL(10,2) NULL | 임계치 (NULL=미사용) |
| UNIQUE(equipment_id, type) | | |

### sensor_data (원본 — 7일 보관, 배치 삭제)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| sensor_id | FK NN | |
| value | DECIMAL(10,2) NN | |
| measured_at | DATETIME(3) NN | |
| INDEX (sensor_id, measured_at) | | 조회 핵심 인덱스 |

### sensor_data_1m (1분 집계 — 영구 보관)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| sensor_id | FK NN | |
| bucket_at | DATETIME NN | 분 단위 절삭 |
| min_v / max_v / avg_v | DECIMAL(10,2) | |
| sample_count | INT | |
| UNIQUE(sensor_id, bucket_at) | | 스케줄러 1분마다 UPSERT |

### alarms
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN | |
| sensor_id | FK NULL | NULL = 시스템 알람(PM 지연 등) |
| alarm_type | ENUM('SENSOR_THRESHOLD','PM_OVERDUE','MANUAL') | |
| severity | ENUM('WARNING','MAJOR','CRITICAL') NN | |
| status | ENUM('OPEN','ACK','RESOLVED') NN DEFAULT 'OPEN' | |
| trigger_value / threshold_value | DECIMAL(10,2) NULL | 발생 당시 값/기준 |
| message | VARCHAR(300) NN | |
| occurred_at | DATETIME NN | |
| ack_by / ack_at | FK users, DATETIME NULL | |
| resolved_by / resolved_at / resolve_note | NULL | 해제 사유 필수(앱 검증) |
| INDEX (equipment_id, status), INDEX (status, occurred_at) | | |

중복 억제 조회: `WHERE equipment_id=? AND sensor_id=? AND severity=? AND status != 'RESOLVED'`.

### inspections (점검 이력)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN | |
| type | ENUM('PM','BM') NN | |
| shift | ENUM('D','N') NN | |
| worker_id | FK users NN | |
| started_at / ended_at | DATETIME NN | ended > started 검증 |
| duration_min | INT | 자동 계산 |
| content | TEXT NN | 점검/고장 내용 |
| action_taken | TEXT NULL | 조치 사항 |
| cause_4m | ENUM('MAN','MACHINE','MATERIAL','METHOD') NULL | BM 필수(앱 검증) |
| cause_detail | VARCHAR(300) NULL | |
| alarm_id | FK NULL | BM-알람 연계 |
| has_ng | BOOLEAN DEFAULT FALSE | 체크리스트 NG 존재 |
| reviewed_by / reviewed_at | NULL | 엔지니어 승인 |
| INDEX (equipment_id, started_at), INDEX (type, shift) | | |

### checklist_items (PM 템플릿) / inspection_check_results
| 테이블 | 컬럼 |
|---|---|
| checklist_items | equipment_id FK NN, item_name VARCHAR(200) NN, criteria VARCHAR(200), seq INT, active BOOLEAN |
| inspection_check_results | inspection_id FK NN, checklist_item_id FK NN, result ENUM('OK','NG','NA') NN, note VARCHAR(300) |

### pm_schedules
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN UNIQUE | 설비당 1개 (MVP) |
| cycle_type | ENUM('DAILY','WEEKLY','MONTHLY') NN | |
| cycle_value | INT NULL | WEEKLY=요일(1~7), MONTHLY=일자(1~28) |
| last_done_at | DATETIME NULL | PM 이력 등록 시 갱신 |
| next_due_at | DATETIME NN | 재계산 저장 |
| overdue_alarm_sent | BOOLEAN DEFAULT FALSE | 지연 알람 중복 방지 |

### ai_reports
| 컬럼 | 타입 | 설명 |
|---|---|---|
| equipment_id | FK NN | |
| alarm_id / inspection_id | FK NULL | 둘 중 하나 이상 |
| title | VARCHAR(200) NN | |
| status | ENUM('GENERATING','DRAFT','CONFIRMED','FAILED') NN | |
| draft_content | MEDIUMTEXT NULL | AI 원본 (보존, 수정 금지) |
| final_content | MEDIUMTEXT NULL | 확정본 |
| fail_reason | VARCHAR(300) NULL | |
| model / prompt_tokens / completion_tokens | 기록용 | 비용 추적 (NFR-4) |
| created_by / confirmed_by | FK users | |

### simulation_scenarios (시뮬레이터 제어)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| sensor_id | FK NN | |
| type | ENUM('DRIFT','SPIKE','STEP') NN | |
| param | JSON | slope/probability/offset 등 |
| active | BOOLEAN NN | 해제 시 FALSE |
| started_at / ended_at | DATETIME | |

## 3. 데이터 보존 정책

| 데이터 | 보존 | 방법 |
|---|---|---|
| sensor_data 원본 | 7일 | 일 1회 배치 삭제 (@Scheduled, 새벽) |
| sensor_data_1m | 영구 | 1분 스케줄러 UPSERT |
| 그 외 전부 | 영구 + soft delete | 이력 시스템 원칙 |

## 4. 시드 데이터 (data.sql / CommandLineRunner)

- 사용자 3명 (admin@/engineer@/tech@fabwatch.dev, 역할별)
- CELL-1라인 > 공정 3개 > 설비 5대 (03 문서 4.1 표 기준) + 센서 18개 + 임계치
- LAMI-01 체크리스트 5항목, 설비별 PM 스케줄
- 데모용 과거 데이터: 점검 이력 20건(PM 15/BM 5), 알람 10건, 상태 로그 (KPI가 빈 화면이 안 되도록)
