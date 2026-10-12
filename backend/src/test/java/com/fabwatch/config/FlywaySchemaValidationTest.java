package com.fabwatch.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V1__init.sql ↔ JPA 엔티티 정합성 가드 (H2, MySQL 모드).
 *
 * Flyway로 스키마를 만든 뒤(ddl-auto=none) 모든 엔티티의 테이블·컬럼이 마이그레이션 결과에 존재하는지 대조한다.
 * 엔티티에 컬럼/테이블을 추가하고 V2 마이그레이션을 빠뜨리면 CI에서 바로 실패한다.
 *
 * 타입까지 보는 Hibernate validate는 H2에서 쓸 수 없다(MySQL의 mediumtext/enum이 H2에서 다른 타입으로 해석돼 오탐).
 * 타입·인덱스까지 포함한 실제 검증(MySQL 8에 V1 적용 + ddl-auto=validate 기동)은 배포 점검 절차에서 수행한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "fabwatch.test.context-isolation=flyway-validate"
})
@ActiveProfiles("test")
class FlywaySchemaValidationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("V1 마이그레이션이 적용된다")
    void V1_적용() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("모든 엔티티의 테이블·컬럼이 Flyway 스키마에 존재한다")
    void 엔티티_컬럼이_마이그레이션에_존재() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        List<String> missing = new ArrayList<>();
        int[] entityCount = {0};

        sessionFactory.getMappingMetamodel().streamEntityDescriptors().forEach(descriptor -> {
            entityCount[0]++;
            AbstractEntityPersister persister = (AbstractEntityPersister) descriptor;
            String table = persister.getTableName().replace("`", "").replace("\"", "");

            Set<String> expected = new HashSet<>();
            for (String column : persister.getIdentifierColumnNames()) {
                expected.add(column.toLowerCase(Locale.ROOT));
            }
            for (String property : persister.getPropertyNames()) {
                for (String column : persister.getPropertyColumnNames(property)) {
                    if (column != null) { // 파생(@Formula) 속성은 null
                        expected.add(column.toLowerCase(Locale.ROOT));
                    }
                }
            }

            Set<String> actual = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT LOWER(column_name) FROM information_schema.columns "
                            + "WHERE table_schema = SCHEMA() AND LOWER(table_name) = ?", String.class,
                    table.toLowerCase(Locale.ROOT)));
            if (actual.isEmpty()) {
                missing.add("테이블 없음: " + table);
                return;
            }
            expected.stream().filter(column -> !actual.contains(column))
                    .forEach(column -> missing.add(table + "." + column));
        });

        assertThat(entityCount[0]).as("검사한 엔티티 수").isEqualTo(18);
        assertThat(missing).as("엔티티에는 있는데 Flyway 스키마에 없는 테이블/컬럼").isEmpty();
    }
}
