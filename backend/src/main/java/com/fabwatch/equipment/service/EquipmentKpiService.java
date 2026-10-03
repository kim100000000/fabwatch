package com.fabwatch.equipment.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.dto.EquipmentKpiListResponse;
import com.fabwatch.equipment.dto.EquipmentKpiResponse;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.repository.EquipmentSpecifications;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.equipment.repository.StatusLogRow;
import com.fabwatch.equipment.service.KpiCalculator.StatusTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 설비 KPI 조회 (docs/03 F-5.4, FR-5.7). 상태 로그는 equipment 도메인 소유라 같은 도메인 repository를 쓴다.
 * 계산 자체는 KpiCalculator(순수 함수)에 위임하고, 여기서는 기간 경계 해석과 로그 조회만 한다.
 * 로그는 설비 전체를 한 번에 조회(기간 내 1쿼리 + 시작 시점 상태 1쿼리)해 N+1을 피한다.
 */
@Service
@RequiredArgsConstructor
public class EquipmentKpiService {

    private final EquipmentRepository equipmentRepository;
    private final LineRepository lineRepository;
    private final EquipmentStatusLogRepository statusLogRepository;

    /** GET /equipments/{id}/kpi */
    @Transactional(readOnly = true)
    public EquipmentKpiResponse getKpi(Long equipmentId, KpiPeriod period) {
        return getKpi(equipmentId, period, Instant.now());
    }

    EquipmentKpiResponse getKpi(Long equipmentId, KpiPeriod period, Instant now) {
        // TENANT: 설비 조회에 tenant_id 필터 추가 지점
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId));
        Instant start = period.startAt(now);
        Map<Long, KpiTotals> totals = calculate(List.of(equipment.getId()), start, now);
        return EquipmentKpiResponse.of(equipment.getId(), period, start, now, totals.get(equipment.getId()));
    }

    /** GET /equipments/kpi?period=&lineId= — 삭제된 설비는 @SQLRestriction으로 제외된다. */
    @Transactional(readOnly = true)
    public EquipmentKpiListResponse getKpiList(KpiPeriod period, Long lineId) {
        return getKpiList(period, lineId, Instant.now());
    }

    EquipmentKpiListResponse getKpiList(KpiPeriod period, Long lineId, Instant now) {
        if (lineId != null && !lineRepository.existsById(lineId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "라인을 찾을 수 없습니다: id=" + lineId);
        }
        // TENANT: 설비 목록 조회에 tenant_id 필터 추가 지점
        List<Equipment> equipments = equipmentRepository.findAll(
                EquipmentSpecifications.inLine(lineId), Sort.by("code"));
        Instant start = period.startAt(now);

        Map<Long, KpiTotals> totalsById = calculate(equipments.stream().map(Equipment::getId).toList(), start, now);

        KpiTotals sum = KpiTotals.EMPTY;
        List<EquipmentKpiListResponse.Item> items = new ArrayList<>(equipments.size());
        for (Equipment equipment : equipments) {
            KpiTotals totals = totalsById.get(equipment.getId());
            sum = sum.plus(totals); // 요약은 합산 후 재계산 (비율의 평균 금지)
            items.add(EquipmentKpiListResponse.Item.of(
                    equipment.getId(), equipment.getCode(), equipment.getName(), totals));
        }
        return new EquipmentKpiListResponse(period, start, now, EquipmentKpiListResponse.Summary.of(sum), items);
    }

    /** 설비별 로그(기간 시작 시점 상태 + 기간 내 전이)를 모아 KpiCalculator에 넘긴다. */
    private Map<Long, KpiTotals> calculate(List<Long> equipmentIds, Instant start, Instant end) {
        Map<Long, KpiTotals> result = new HashMap<>();
        if (equipmentIds.isEmpty()) {
            return result;
        }
        Map<Long, List<StatusTransition>> logsById = new HashMap<>();
        for (StatusLogRow row : statusLogRepository.findStateAtPeriodStart(equipmentIds, start)) {
            logsById.computeIfAbsent(row.equipmentId(), key -> new ArrayList<>())
                    .add(new StatusTransition(row.toStatus(), row.changedAt()));
        }
        for (StatusLogRow row : statusLogRepository.findTransitionsInPeriod(equipmentIds, start, end)) {
            logsById.computeIfAbsent(row.equipmentId(), key -> new ArrayList<>())
                    .add(new StatusTransition(row.toStatus(), row.changedAt()));
        }
        for (Long id : equipmentIds) {
            result.put(id, KpiCalculator.calculate(logsById.getOrDefault(id, List.of()), start, end));
        }
        return result;
    }
}
