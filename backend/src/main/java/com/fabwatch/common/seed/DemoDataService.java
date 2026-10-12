package com.fabwatch.common.seed;

import com.fabwatch.auth.entity.Role;
import com.fabwatch.auth.entity.User;
import com.fabwatch.auth.repository.UserRepository;
import com.fabwatch.common.util.PasswordPolicy;
import com.fabwatch.equipment.entity.Equipment;
import com.fabwatch.equipment.entity.EquipmentStatusLog;
import com.fabwatch.equipment.repository.EquipmentStatusLogRepository;
import com.fabwatch.equipment.repository.LineRepository;
import com.fabwatch.sensor.entity.Sensor;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 공개 데모 배포용 옵트인 데이터 로더 (DEMO_DATA_ENABLED=true 에서만 빈이 생긴다, local 제외).
 *
 * 운영 DB에는 라인/공정/센서를 만드는 API가 없어서(설비 등록은 기존 공정이 필요하고 센서 생성 경로가 시더뿐), 포트폴리오 시연용
 * 최소 마스터 데이터만 넣는다: CELL-1라인·공정 3·설비 5·센서 18+임계치·PM 체크리스트·PM 스케줄(+설비별 최초 상태 이력).
 * 알람·점검 이력·AI 리포트 같은 "과거 이력"은 만들지 않는다(실제 사용·시뮬레이터로 쌓인다). 값은 전부 가상값이다.
 *
 * - 멱등: (삭제되지 않은) lines가 비어 있을 때만 마스터 데이터를 만든다.
 * - 사용자: 이 로더는 users를 만들지 않는다. 단 DEMO_ACCOUNTS_PASSWORD가 있으면 ENGINEER·TECHNICIAN 데모 계정 2개만 만든다
 *   (이미 있으면 건너뜀). ADMIN은 절대 만들지 않는다 — ADMIN은 BOOTSTRAP_ADMIN_* 로 만든 비공개 계정뿐이다.
 * - PM 스케줄은 기동 시점 기준 미래로 잡아, 시작하자마자 5대가 OVERDUE 알람을 내지 않게 한다.
 *
 * common/seed는 도메인 엔티티 직접 접근이 허용된 시더 전용 예외 지점이다.
 */
@Slf4j
@Service
@Profile("!local")
@ConditionalOnProperty(name = "fabwatch.demo.enabled", havingValue = "true")
public class DemoDataService {

    static final String ENGINEER_EMAIL = "engineer@fabwatch.dev";
    static final String TECH_EMAIL = "tech@fabwatch.dev";

    private final EntityManager em;
    private final LineRepository lineRepository;
    private final EquipmentStatusLogRepository statusLogRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataProperties properties;

    public DemoDataService(EntityManager em, LineRepository lineRepository,
                           EquipmentStatusLogRepository statusLogRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder, DemoDataProperties properties) {
        this.em = em;
        this.lineRepository = lineRepository;
        this.statusLogRepository = statusLogRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** @param masterDataLoaded 이번 실행에서 마스터 데이터를 만들었는가 / @param accountsCreated 새로 만든 데모 계정 수 */
    public record Result(boolean masterDataLoaded, int accountsCreated) {
    }

    @Transactional
    public Result load() {
        // 쓰기 전에 설정부터 검증한다 — 잘못된 비밀번호로 반쯤 만들어진 상태가 되지 않게 (값은 메시지에 넣지 않는다)
        if (properties.hasAccountsPassword() && !PasswordPolicy.isValid(properties.accountsPassword())) {
            throw new IllegalStateException("DEMO_ACCOUNTS_PASSWORD가 비밀번호 정책(8자 이상, 영문+숫자 포함)에 맞지 않습니다.");
        }

        boolean loaded = loadMasterData();
        int accounts = properties.hasAccountsPassword() ? createDemoAccounts() : 0;
        return new Result(loaded, accounts);
    }

    private boolean loadMasterData() {
        // 삭제되지 않은 라인 기준. 라인을 전부 soft delete한 뒤 재기동하면 설비 코드 UNIQUE에 걸려 로드가 롤백된다(Runner가 로그만 남기고 넘어감)
        if (lineRepository.count() > 0) {
            log.info("데모 데이터 건너뜀 — 라인이 이미 존재합니다.");
            return false;
        }

        DemoMasterData masterData = new DemoMasterData(em, lineRepository);
        Map<String, Equipment> equipments = masterData.seedLineTree(null); // 담당자 없음 (users를 만들지 않으므로)
        Map<String, List<Sensor>> sensors = masterData.seedSensors(equipments);
        masterData.seedChecklist(equipments.get("LAMI-01"));
        masterData.seedPmSchedules(equipments, false); // 시작하자마자 OVERDUE가 되지 않게 전부 미래

        // 최초 상태를 이력에 남긴다 (실제 설비 등록 API와 동일 — KPI 계산의 시작점)
        Instant now = Instant.now();
        equipments.values().forEach(equipment -> statusLogRepository.save(
                new EquipmentStatusLog(equipment, null, equipment.getStatus(), "설비 등록", null, now)));
        statusLogRepository.flush();

        log.info("데모 데이터 생성 완료 — 설비 {}대, 센서 {}개 (가상 데이터)",
                equipments.size(), sensors.values().stream().mapToInt(List::size).sum());
        return true;
    }

    /** ENGINEER·TECHNICIAN 2계정. ADMIN은 만들지 않는다. 이미 있으면 건너뜀 (비밀번호는 로그에 남기지 않는다) */
    private int createDemoAccounts() {
        int created = 0;
        created += createAccountIfAbsent(ENGINEER_EMAIL, "박엔지니어", Role.ENGINEER);
        created += createAccountIfAbsent(TECH_EMAIL, "이테크니션", Role.TECHNICIAN);
        return created;
    }

    private int createAccountIfAbsent(String email, String name, Role role) {
        if (userRepository.existsByEmail(email)) {
            log.info("데모 계정 건너뜀 — 이미 존재: {} ({})", email, role);
            return 0;
        }
        // TENANT: 멀티테넌트 전환 시 테넌트별 데모 계정 생성 지점
        userRepository.saveAndFlush(User.builder()
                .email(email)
                .password(passwordEncoder.encode(properties.accountsPassword()))
                .name(name)
                .role(role)
                .enabled(true)
                .build());
        log.info("데모 계정 생성: {} ({}) — 비밀번호는 출력하지 않습니다", email, role);
        return 1;
    }
}
