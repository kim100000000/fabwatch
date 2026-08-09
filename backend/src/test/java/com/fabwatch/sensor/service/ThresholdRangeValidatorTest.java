package com.fabwatch.sensor.service;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 임계치 순서 검증 (docs/03 F-2 FR-2.3): crit_low ≤ warn_low &lt; warn_high ≤ crit_high.
 */
class ThresholdRangeValidatorTest {

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private void validate(String warnLow, String warnHigh, String critLow, String critHigh) {
        ThresholdRangeValidator.validate(d(warnLow), d(warnHigh), d(critLow), d(critHigh));
    }

    @Test
    @DisplayName("정상 순서는 통과한다")
    void 정상_순서() {
        assertThatCode(() -> validate("38", "50", "35", "55")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("crit == warn 인 경계(등호 허용 구간)도 통과한다")
    void 등호_허용() {
        assertThatCode(() -> validate("38", "50", "38", "50")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("crit_low > warn_low 면 400 INVALID_THRESHOLD_RANGE")
    void 하한_역전() {
        assertThatThrownBy(() -> validate("35", "50", "38", "55"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_THRESHOLD_RANGE);
    }

    @Test
    @DisplayName("warn_high > crit_high 면 거부")
    void 상한_역전() {
        assertThatThrownBy(() -> validate("38", "60", "35", "55"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("warn_low == warn_high 는 거부 (엄격 부등호)")
    void 중앙_동일값() {
        assertThatThrownBy(() -> validate("50", "50", "35", "55"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("warn_low > warn_high 는 거부")
    void 중앙_역전() {
        assertThatThrownBy(() -> validate("52", "50", "35", "55"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("NULL은 미사용이므로 건너뛴다 — 상한만 쓰는 진동 센서")
    void 하한_NULL_허용() {
        assertThatCode(() -> validate(null, "3.5", null, "5.0")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한쪽이 NULL이어도 남은 값들의 상대 순서는 지켜야 한다")
    void NULL_이어도_교차_금지() {
        // warn_low 없음 + crit_low(60)가 warn_high(50)보다 큼 → 거부
        assertThatThrownBy(() -> validate(null, "50", "60", "70"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("전부 NULL(임계치 미사용)은 통과한다")
    void 전부_NULL() {
        assertThatCode(() -> validate(null, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("음수 기준(진공 압력)도 같은 규칙으로 검증된다")
    void 음수_범위() {
        assertThatCode(() -> validate("-105", "-85", "-110", "-80")).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate("-105", "-85", "-100", "-80"))
                .isInstanceOf(BusinessException.class);
    }
}
