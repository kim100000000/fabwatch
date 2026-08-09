package com.fabwatch.sensor.service;

import java.time.Instant;
import java.util.List;

/**
 * 센서 값 공급원 포트 (docs/10 ADR-8).
 *
 * "실설비가 없어서 시뮬레이터를 쓰지만, SensorDataSource 인터페이스 뒤에 두면 실제 PLC 어댑터
 * (OPC-UA/Modbus)로 교체 가능하다. 가짜 데이터가 아니라 교체 가능한 데이터 소스 구현체 중 하나."
 *
 * 현재 구현체는 {@code com.fabwatch.simulator.service.SimulatorSensorDataSource} 하나뿐이며,
 * 수집 파이프라인(저장 → SSE 발행 → 임계치 판정)은 구현체를 전혀 모른다.
 * ※ 읽기 전용 포트다. 설비로 값을 쓰는(write) 메서드는 정의하지 않는다 — docs/11 §10 안전 게이트.
 */
public interface SensorDataSource {

    /** 이 시점의 전체 센서 값을 한 번에 읽는다. 값이 없으면 빈 리스트. */
    List<SensorReading> read(Instant at);

    /** 로그·헬스체크용 소스 이름 (예: "SIMULATOR") */
    String sourceName();
}
