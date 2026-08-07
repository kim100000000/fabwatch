package com.fabwatch.equipment.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.List;

/**
 * 라인 (docs/05 lines) — 예: CELL-1라인
 * // TENANT: 멀티테넌트 전환 시 tenant_id 추가 지점
 */
// `lines`는 MySQL 예약어라 백틱으로 인용한다 (H2 MySQL 모드도 동일 표기 허용)
@Entity
@Table(name = "`lines`")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE `lines` SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Line extends SoftDeletableEntity {

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL)
    @OrderBy("seq asc")
    private List<Process> processes = new ArrayList<>();

    public Line(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void addProcess(Process process) {
        processes.add(process);
        process.assignLine(this);
    }
}
