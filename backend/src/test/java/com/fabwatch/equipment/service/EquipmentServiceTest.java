package com.fabwatch.equipment.service;

import com.fabwatch.auth.service.UserQueryService;
import com.fabwatch.equipment.dto.EquipmentDetailResponse;
import com.fabwatch.equipment.dto.EquipmentStatusChangeRequest;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatus;
import com.fabwatch.equipment.entity.EquipmentStatusLog;
import com.fabwatch.equipment.entity.Line;
import com.fabwatch.equipment.entity.Process;
import com.fabwatch.equipment.repository.EquipmentRepository;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.equipment.repository.ProcessRepository;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설비 상태 전환 서비스 테스트 — 전환 성공 시 반드시 status log가 남아야 한다(KPI 원천).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EquipmentServiceTest {

    private static final Long EQUIPMENT_ID = 10L;
    private static final Long ACTOR_ID = 2L;

    @Mock
    private EquipmentRepository equipmentRepository;
    @Mock
    private ProcessRepository processRepository;
    @Mock
    private LineRepository lineRepository;
    @Mock
    private EquipmentStatusLogRepository statusLogRepository;
    @Mock
    private UserQueryService userQueryService;
    /** 상태 변경 시 EquipmentStatusChangedEvent를 발행한다 (SSE `status` 이벤트의 출처) */
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EquipmentService equipmentService;

    private Equipment equipment;

    @BeforeEach
    void setUp() {
        Line line = new Line("CELL-1라인");
        ReflectionTestUtils.setField(line, "id", 1L);
        Process process = new Process("합착", 1);
        ReflectionTestUtils.setField(process, "id", 1L);
        line.addProcess(process);

        equipment = Equipment.builder()
                .code("LAMI-01")
                .name("합착기 1호기")
                .status(EquipmentStatus.RUN)
                .build();
        ReflectionTestUtils.setField(equipment, "id", EQUIPMENT_ID);
        process.addEquipment(equipment);

        given(equipmentRepository.findById(EQUIPMENT_ID)).willReturn(Optional.of(equipment));
        given(userQueryService.findNameById(any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("허용 전이(RUN→DOWN) — 상태가 바뀌고 상태 로그가 남는다")
    void 상태전환_성공시_로그기록() {
        EquipmentDetailResponse response = equipmentService.changeStatus(
                EQUIPMENT_ID, new EquipmentStatusChangeRequest(EquipmentStatus.DOWN, "수동 고장 보고"), ACTOR_ID);

        assertThat(response.status()).isEqualTo("DOWN");
        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.DOWN);

        ArgumentCaptor<EquipmentStatusLog> captor = ArgumentCaptor.forClass(EquipmentStatusLog.class);
        verify(statusLogRepository).save(captor.capture());
        EquipmentStatusLog saved = captor.getValue();
        assertThat(saved.getFromStatus()).isEqualTo(EquipmentStatus.RUN);
        assertThat(saved.getToStatus()).isEqualTo(EquipmentStatus.DOWN);
        assertThat(saved.getReason()).isEqualTo("수동 고장 보고");
        assertThat(saved.getChangedBy()).isEqualTo(ACTOR_ID);
        assertThat(saved.getChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("비허용 전이(DOWN→RUN) — INVALID_STATUS_TRANSITION이고 로그도 남기지 않는다")
    void 상태전환_실패시_로그없음() {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.DOWN);

        assertThatThrownBy(() -> equipmentService.changeStatus(
                EQUIPMENT_ID, new EquipmentStatusChangeRequest(EquipmentStatus.RUN, "임의 복구"), ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.DOWN);
        verify(statusLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 설비 — NOT_FOUND")
    void 없는_설비_조회() {
        given(equipmentRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> equipmentService.getEquipment(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
