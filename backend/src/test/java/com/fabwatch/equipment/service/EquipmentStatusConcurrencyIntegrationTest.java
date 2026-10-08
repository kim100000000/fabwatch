package com.fabwatch.equipment.service;

import com.fabwatch.equipment.dto.EquipmentStatusChangeRequest;
import com.fabwatch.equipment.entity.EquipmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 설비의 동시 상태 변경 (안정성 감사 M-1): 수동 전환(changeStatus)과 수집 틱의 자동 DOWN(autoDown)이 겹쳐도
 * 설비 행 락(PESSIMISTIC_WRITE) 덕에 직렬화되어 상태 로그 체인과 최종 equipments.status가 어긋나지 않는다.
 *
 * 한계: H2의 행 락은 MySQL(InnoDB)과 구현이 달라 이 테스트가 "락이 없으면 반드시 실패"를 보증하지는 못한다.
 * 락 메서드 사용 자체는 EquipmentServiceTest(findByIdForUpdate만 호출)가 단위로 보증한다.
 */
@SpringBootTest(properties = {"fabwatch.seed.enabled=true", "fabwatch.test.marker=equipment-concurrency"})
@ActiveProfiles({"local", "test"})
class EquipmentStatusConcurrencyIntegrationTest {

    private static final int ROUNDS = 25;

    @Autowired
    private EquipmentService equipmentService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("★ 수동 RUN→IDLE과 자동 DOWN이 동시에 들어와도 로그 체인(from==이전 to)과 최종 상태가 항상 일치한다")
    void 동시_상태변경_로그_일관성() throws Exception {
        long equipmentId = jdbc.queryForObject("SELECT id FROM equipments WHERE code = 'LAMI-01'", Long.class);
        long engineerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'engineer@fabwatch.dev'", Long.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                jdbc.update("UPDATE equipments SET status = 'RUN' WHERE id = ?", equipmentId);
                long lastLogId = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM equipment_status_logs WHERE equipment_id = ?", Long.class, equipmentId);

                CyclicBarrier start = new CyclicBarrier(2);
                Callable<Object> manual = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return equipmentService.changeStatus(equipmentId,
                                new EquipmentStatusChangeRequest(EquipmentStatus.IDLE, "수동 전환"), engineerId, "ENGINEER");
                    } catch (RuntimeException e) {
                        return e; // 먼저 DOWN이 된 뒤라면 DOWN→IDLE 은 사유 필수 등으로 거절될 수 있다 (정상)
                    }
                };
                Callable<Object> auto = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return equipmentService.autoDown(equipmentId, "CRITICAL 알람 자동 DOWN", Instant.now());
                };
                List<Future<Object>> results = pool.invokeAll(List.of(manual, auto), 30, TimeUnit.SECONDS);
                for (Future<Object> result : results) {
                    result.get(); // 예외 없이 끝나야 한다 (데드락·락 타임아웃 없음)
                }

                List<Map<String, Object>> logs = jdbc.queryForList(
                        "SELECT from_status, to_status, changed_at FROM equipment_status_logs "
                                + "WHERE equipment_id = ? AND id > ? ORDER BY id", equipmentId, lastLogId);
                assertThat(logs).as("round %d: 최소 1건 전환 기록", round).isNotEmpty();
                assertThat(logs.get(0).get("FROM_STATUS")).as("round %d 첫 로그는 RUN에서 시작", round).isEqualTo("RUN");
                for (int i = 1; i < logs.size(); i++) {
                    assertThat(logs.get(i).get("FROM_STATUS")).as("round %d: 로그 체인 연속성", round)
                            .isEqualTo(logs.get(i - 1).get("TO_STATUS"));
                }
                String finalStatus = jdbc.queryForObject("SELECT status FROM equipments WHERE id = ?", String.class, equipmentId);
                assertThat(finalStatus).as("round %d: 최종 상태 == 마지막 로그의 to", round)
                        .isEqualTo(logs.get(logs.size() - 1).get("TO_STATUS"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
