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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.fabwatch.common.event.EquipmentStatusChangedEvent;
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
import static org.mockito.Mockito.times;
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
                EQUIPMENT_ID, new EquipmentStatusChangeRequest(EquipmentStatus.DOWN, "수동 고장 보고"), ACTOR_ID, "ENGINEER");

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
    @DisplayName("비허용 전이(PM→RUN) — INVALID_STATUS_TRANSITION이고 로그도 남기지 않는다 (SM-5)")
    void 상태전환_실패시_로그없음() {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.PM);

        assertThatThrownBy(() -> equipmentService.changeStatus(
                EQUIPMENT_ID, new EquipmentStatusChangeRequest(EquipmentStatus.RUN, "임의 복구"), ACTOR_ID, "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.PM);
        verify(statusLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "SM-3a {0} DOWN→IDLE 허용 + 로그 1건")
    @ValueSource(strings = {"ADMIN", "ENGINEER", "TECHNICIAN"})
    @DisplayName("SM-3a: DOWN→IDLE은 TECHNICIAN 포함 전 역할 허용")
    void DOWN_IDLE_전_역할_허용(String role) {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.DOWN);

        equipmentService.changeStatus(EQUIPMENT_ID,
                new EquipmentStatusChangeRequest(EquipmentStatus.IDLE, "베어링 교체 완료"), ACTOR_ID, role);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.IDLE);
        ArgumentCaptor<EquipmentStatusLog> captor = ArgumentCaptor.forClass(EquipmentStatusLog.class);
        verify(statusLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(EquipmentStatus.DOWN);
        assertThat(captor.getValue().getToStatus()).isEqualTo(EquipmentStatus.IDLE);
        assertThat(captor.getValue().getReason()).isEqualTo("베어링 교체 완료");
    }

    @ParameterizedTest(name = "SM-3b/SM-6 reason=[{1}] → VALIDATION_ERROR")
    @CsvSource(value = {"IDLE,NULL", "IDLE,''", "IDLE,'   '", "RUN,NULL", "RUN,''", "RUN,'   '"}, nullValues = "NULL")
    @DisplayName("SM-3b/SM-6: DOWN→IDLE/DOWN→RUN 사유 null·공백은 400 VALIDATION_ERROR이고 상태·로그 불변")
    void DOWN_복귀_사유_필수(EquipmentStatus to, String reason) {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.DOWN);

        assertThatThrownBy(() -> equipmentService.changeStatus(
                EQUIPMENT_ID, new EquipmentStatusChangeRequest(to, reason), ACTOR_ID, "ENGINEER"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.DOWN);
        verify(statusLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "SM-6 {0} DOWN→RUN 허용 + 로그 1건")
    @ValueSource(strings = {"ADMIN", "ENGINEER"})
    @DisplayName("SM-6: DOWN→RUN은 ENGINEER 이상 허용 — 로그의 DOWN→RUN 전이가 시운전 생략 표시")
    void DOWN_RUN_엔지니어_이상_허용(String role) {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.DOWN);

        equipmentService.changeStatus(EQUIPMENT_ID,
                new EquipmentStatusChangeRequest(EquipmentStatus.RUN, "긴급 생산 재개, 시운전 생략"), ACTOR_ID, role);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.RUN);
        ArgumentCaptor<EquipmentStatusLog> captor = ArgumentCaptor.forClass(EquipmentStatusLog.class);
        verify(statusLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(EquipmentStatus.DOWN);
        assertThat(captor.getValue().getToStatus()).isEqualTo(EquipmentStatus.RUN);
    }

    @Test
    @DisplayName("SM-6: DOWN→RUN을 TECHNICIAN이 요청하면 403 FORBIDDEN, 상태·로그 불변")
    void DOWN_RUN_테크니션_거부() {
        ReflectionTestUtils.setField(equipment, "status", EquipmentStatus.DOWN);

        assertThatThrownBy(() -> equipmentService.changeStatus(EQUIPMENT_ID,
                new EquipmentStatusChangeRequest(EquipmentStatus.RUN, "시운전 생략"), ACTOR_ID, "TECHNICIAN"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.DOWN);
        verify(statusLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "TECHNICIAN {0}→{1} 은 403 (DOWN→IDLE 외 전부 ENGINEER+)")
    @CsvSource({"RUN,IDLE", "RUN,DOWN", "RUN,PM", "IDLE,RUN", "IDLE,DOWN", "IDLE,PM", "DOWN,RUN", "DOWN,PM", "PM,IDLE"})
    @DisplayName("TECHNICIAN은 허용된 전이 중 DOWN→IDLE만 수행할 수 있다")
    void 테크니션_권한_범위(EquipmentStatus from, EquipmentStatus to) {
        ReflectionTestUtils.setField(equipment, "status", from);

        assertThatThrownBy(() -> equipmentService.changeStatus(EQUIPMENT_ID,
                new EquipmentStatusChangeRequest(to, "사유"), ACTOR_ID, "TECHNICIAN"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(equipment.getStatus()).isEqualTo(from);
        verify(statusLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "SM-7 {0}→{1} 거부")
    @CsvSource({"RUN,RUN", "IDLE,IDLE", "DOWN,DOWN", "PM,PM", "PM,RUN", "PM,DOWN"})
    @DisplayName("SM-7: 허용표 밖 전환은 어떤 역할이든 400 INVALID_STATUS_TRANSITION")
    void 허용외_전이_전수_거부(EquipmentStatus from, EquipmentStatus to) {
        for (String role : new String[]{"ADMIN", "ENGINEER", "TECHNICIAN"}) {
            ReflectionTestUtils.setField(equipment, "status", from);

            assertThatThrownBy(() -> equipmentService.changeStatus(EQUIPMENT_ID,
                    new EquipmentStatusChangeRequest(to, "사유"), ACTOR_ID, role))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
            assertThat(equipment.getStatus()).isEqualTo(from);
        }
        verify(statusLogRepository, never()).save(any());
    }

    @ParameterizedTest(name = "SM-8 {0}→{1} 성공 시 로그 1건")
    @CsvSource({"RUN,IDLE", "RUN,DOWN", "RUN,PM", "IDLE,RUN", "IDLE,DOWN", "IDLE,PM",
            "DOWN,IDLE", "DOWN,RUN", "DOWN,PM", "PM,IDLE"})
    @DisplayName("SM-8: 허용 전이 10개 전부 성공 시 상태 로그가 정확히 1건 남고 이벤트가 발행된다")
    void 성공_전이마다_로그_1건(EquipmentStatus from, EquipmentStatus to) {
        ReflectionTestUtils.setField(equipment, "status", from);

        equipmentService.changeStatus(EQUIPMENT_ID,
                new EquipmentStatusChangeRequest(to, "사유"), ACTOR_ID, "ENGINEER");

        assertThat(equipment.getStatus()).isEqualTo(to);
        ArgumentCaptor<EquipmentStatusLog> captor = ArgumentCaptor.forClass(EquipmentStatusLog.class);
        verify(statusLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(from);
        assertThat(captor.getValue().getToStatus()).isEqualTo(to);
        verify(eventPublisher, times(1)).publishEvent(any(EquipmentStatusChangedEvent.class));
    }

    @Test
    @DisplayName("자동 DOWN(autoDown)은 역할 검사 대상이 아니며 RUN→DOWN으로 로그(changed_by=null)를 남긴다")
    void 자동_DOWN_기존_동작_유지() {
        boolean changed = equipmentService.autoDown(EQUIPMENT_ID, "CRITICAL 알람", java.time.Instant.now());

        assertThat(changed).isTrue();
        assertThat(equipment.getStatus()).isEqualTo(EquipmentStatus.DOWN);
        ArgumentCaptor<EquipmentStatusLog> captor = ArgumentCaptor.forClass(EquipmentStatusLog.class);
        verify(statusLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getChangedBy()).isNull();
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
