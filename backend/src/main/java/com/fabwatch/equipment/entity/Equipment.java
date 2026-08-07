package com.fabwatch.equipment.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

/**
 * 설비 (docs/05 equipments, docs/03 F-2).
 * // TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
 *
 * manager_id는 auth 도메인(users)의 FK지만, 도메인 간 직접 참조 금지 원칙에 따라
 * 연관관계 매핑 없이 ID만 보관한다. 이름이 필요하면 auth의 UserQueryService로 조회한다.
 */
@Entity
@Table(name = "equipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE equipments SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Equipment extends SoftDeletableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "process_id", nullable = false)
    private Process process;

    @Column(name = "code", length = 30, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "maker", length = 100)
    private String maker;

    @Column(name = "installed_at")
    private LocalDate installedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private EquipmentStatus status = EquipmentStatus.IDLE;

    /** 담당 엔지니어 users.id (FK 매핑 없이 ID만 보관) */
    @Column(name = "manager_id")
    private Long managerId;

    /** 비고 (docs/03 F-2 설비 필드) — docs/05 테이블 정의에는 없어 추가한 컬럼 */
    @Column(name = "note", length = 500)
    private String note;

    @Builder
    private Equipment(String code, String name, String modelName, String maker,
                      LocalDate installedAt, EquipmentStatus status, Long managerId, String note) {
        this.code = code;
        this.name = name;
        this.modelName = modelName;
        this.maker = maker;
        this.installedAt = installedAt;
        this.status = status == null ? EquipmentStatus.IDLE : status;
        this.managerId = managerId;
        this.note = note;
    }

    void assignProcess(Process process) {
        this.process = process;
    }

    public void moveToProcess(Process process) {
        this.process = process;
    }

    /** 설비 코드는 물리 설비 식별자라 수정 대상에서 제외한다 (docs/03 F-2 "설비코드(고유)"). */
    public void updateBasicInfo(String name, String modelName, String maker,
                                LocalDate installedAt, Long managerId, String note) {
        this.name = name;
        this.modelName = modelName;
        this.maker = maker;
        this.installedAt = installedAt;
        this.managerId = managerId;
        this.note = note;
    }

    /**
     * 상태 전환 — 허용되지 않는 전이는 여기서 전부 차단한다 (docs/03 F-2).
     * 전환 기록(status log) 생성은 서비스 책임.
     */
    public EquipmentStatus changeStatus(EquipmentStatus to) {
        if (!status.canTransitionTo(to)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "허용되지 않는 상태 전환입니다: " + status + " → " + to
                            + " (허용: " + status.allowedTargets() + ")");
        }
        EquipmentStatus from = this.status;
        this.status = to;
        return from;
    }
}
