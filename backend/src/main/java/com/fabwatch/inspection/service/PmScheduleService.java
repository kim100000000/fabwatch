package com.fabwatch.inspection.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import com.fabwatch.equipment.service.EquipmentQueryService;
import com.fabwatch.inspection.dto.PmScheduleResponse;
import com.fabwatch.inspection.dto.PmScheduleUpdateRequest;
import com.fabwatch.inspection.entity.PmSchedule;
import com.fabwatch.inspection.repository.PmScheduleRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PM 스케줄 서비스 (docs/03 F-3.3, docs/06 §5). 날짜 계산은 PmScheduleCalculator(순수 함수)에 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmScheduleService {

    private final PmScheduleRepository pmScheduleRepository;
    private final EquipmentQueryService equipmentQueryService;

    /** GET /pm-schedules — 예정일 빠른 순. overdueOnly=true면 예정일이 지난 것만. */
    @Transactional(readOnly = true)
    public List<PmScheduleResponse> getSchedules(boolean overdueOnly, Long equipmentId) {
        Instant now = Instant.now();
        List<PmSchedule> schedules = pmScheduleRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (equipmentId != null) {
                predicates.add(cb.equal(root.get("equipmentId"), equipmentId));
            }
            if (overdueOnly) {
                predicates.add(cb.lessThan(root.get("nextDueAt"), now));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        }, Sort.by(Sort.Order.asc("nextDueAt"), Sort.Order.asc("id")));

        List<Long> equipmentIds = schedules.stream().map(PmSchedule::getEquipmentId).toList();
        Map<Long, String> codes = equipmentQueryService.findCodesByIds(equipmentIds);
        Map<Long, String> names = equipmentQueryService.findNamesByIds(equipmentIds);
        return schedules.stream()
                // 삭제된 설비의 스케줄은 노출하지 않는다 (설비 조회에서 빠짐)
                .filter(s -> codes.containsKey(s.getEquipmentId()))
                .map(s -> PmScheduleResponse.from(s, codes.get(s.getEquipmentId()), names.get(s.getEquipmentId()), now))
                .toList();
    }

    /**
     * PUT /equipments/{id}/pm-schedule — 설정/변경(없으면 생성). next_due_at 재계산.
     * 기준 시각은 last_done_at, 한 번도 수행한 적이 없으면 지금.
     */
    @Transactional
    public PmScheduleResponse upsert(Long equipmentId, PmScheduleUpdateRequest request) {
        String code = equipmentQueryService.findCodeById(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "설비를 찾을 수 없습니다: id=" + equipmentId));
        PmScheduleCalculator.validate(request.cycleType(), request.cycleValue());

        Instant now = Instant.now();
        PmSchedule schedule = pmScheduleRepository.findByEquipmentId(equipmentId).orElse(null);
        Instant base = (schedule != null && schedule.getLastDoneAt() != null) ? schedule.getLastDoneAt() : now;
        Instant nextDue = PmScheduleCalculator.nextDueAt(request.cycleType(), request.cycleValue(), base);

        if (schedule == null) {
            // TENANT: 설비 소속 테넌트 기준으로 생성하는 지점
            schedule = pmScheduleRepository.save(PmSchedule.builder()
                    .equipmentId(equipmentId)
                    .cycleType(request.cycleType())
                    .cycleValue(request.cycleValue())
                    .nextDueAt(nextDue)
                    .overdueAlarmSent(false)
                    .build());
        } else {
            schedule.reschedule(request.cycleType(), request.cycleValue(), nextDue);
        }
        log.info("PM 스케줄 설정: equipmentId={}, cycle={}/{}, nextDueAt={}",
                equipmentId, request.cycleType(), request.cycleValue(), nextDue);
        String name = equipmentQueryService.findNameById(equipmentId).orElse(null);
        return PmScheduleResponse.from(schedule, code, name, now);
    }
}
