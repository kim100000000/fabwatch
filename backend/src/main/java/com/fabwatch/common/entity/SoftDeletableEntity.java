package com.fabwatch.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.Instant;

/**
 * soft delete 대상 엔티티의 상위 클래스 (docs/03 F-7 — 이력 시스템에서 물리 삭제 금지).
 * 하위 엔티티에는 @SQLDelete + @SQLRestriction("deleted_at IS NULL")을 함께 선언한다.
 */
@Getter
@MappedSuperclass
public abstract class SoftDeletableEntity extends BaseEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 논리 삭제. repository.delete() 대신 이 메서드 또는 @SQLDelete 경로만 사용한다. */
    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
