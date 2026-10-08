package com.fabwatch.simulator.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * 시뮬레이터 시나리오 (docs/05 simulation_scenarios, docs/03 F-4.2).
 * param은 JSON 문자열(slope/probability/offset 등) — MySQL JSON 대신 TEXT로 저장해
 * H2(테스트)와 MySQL(운영) 양쪽에서 동일하게 동작하도록 한다.
 */
@Entity
@Table(name = "simulation_scenarios",
        indexes = @Index(name = "idx_scenario_sensor", columnList = "sensor_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE simulation_scenarios SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class SimulationScenario extends SoftDeletableEntity {

    public enum Type {
        DRIFT, SPIKE, STEP
    }

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10, nullable = false)
    private Type type;

    @Column(name = "param", length = 1000)
    private String param;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** 해제 — 정상 복귀 (docs/06 §8 DELETE). 행은 이력으로 남기고 active=false로만 끈다. */
    public void deactivate(Instant endedAt) {
        this.active = false;
        this.endedAt = endedAt;
    }

    @Builder
    private SimulationScenario(Long sensorId, Type type, String param, boolean active,
                               Instant startedAt, Instant endedAt) {
        this.sensorId = sensorId;
        this.type = type;
        this.param = param;
        this.active = active;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }
}
