package com.fabwatch.equipment.entity;

import com.fabwatch.common.entity.SoftDeletableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * 공정 (docs/05 processes) — 예: 합착, 절단, 검사. seq는 화면 표시 순서.
 */
@Entity
@Table(name = "processes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE processes SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Process extends SoftDeletableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private Line line;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "seq")
    private Integer seq;

    @OneToMany(mappedBy = "process", cascade = CascadeType.ALL)
    @OrderBy("code asc")
    private List<Equipment> equipments = new ArrayList<>();

    public Process(String name, Integer seq) {
        this.name = name;
        this.seq = seq;
    }

    void assignLine(Line line) {
        this.line = line;
    }

    public void addEquipment(Equipment equipment) {
        equipments.add(equipment);
        equipment.assignProcess(this);
    }
}
