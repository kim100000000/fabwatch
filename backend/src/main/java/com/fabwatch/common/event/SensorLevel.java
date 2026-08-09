package com.fabwatch.common.event;

/**
 * 센서 값의 임계치 판정 결과 (docs/03 F-4.3).
 * 특정 도메인에 속하지 않고 sensor(SSE payload)·alarm(심각도 변환) 양쪽이 함께 쓰므로 common/event에 둔다.
 */
public enum SensorLevel {

    NORMAL,
    WARNING,
    CRITICAL;

    public boolean isAbnormal() {
        return this != NORMAL;
    }
}
