package com.fabwatch.equipment.repository;

import com.fabwatch.equipment.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

// TENANT: 목록/상세 조회에 tenant_id 필터 추가 지점
public interface EquipmentRepository extends JpaRepository<Equipment, Long>, JpaSpecificationExecutor<Equipment> {

    Optional<Equipment> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * 상태 전환 전용 조회 — 행 락(SELECT ... FOR UPDATE)을 잡은 최신 상태를 돌려준다.
     * 수동 전환(changeStatus)과 수집 틱의 자동 DOWN(autoDown)이 같은 설비를 동시에 바꿔도 직렬화되어,
     * 락을 얻은 뒤의 최신 상태로 전이를 판정하므로 상태 로그와 equipments.status가 어긋나지 않는다 (안정성 감사 M-1).
     * 반드시 쓰기 트랜잭션 안에서 호출한다. soft delete된 설비는 @SQLRestriction으로 제외된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Equipment e WHERE e.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);
}
