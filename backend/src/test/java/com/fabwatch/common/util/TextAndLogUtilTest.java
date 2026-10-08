package com.fabwatch.common.util;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextAndLogUtilTest {

    @Test
    @DisplayName("truncate: 한도 이하는 그대로, 초과는 말줄임표 포함 정확히 max자")
    void truncate() {
        assertThat(TextUtil.truncate(null, 5)).isNull();
        assertThat(TextUtil.truncate("abcde", 5)).isEqualTo("abcde");
        assertThat(TextUtil.truncate("abcdef", 5)).isEqualTo("abcd…").hasSize(5);
        String reason = "CRITICAL 알람 자동 DOWN — " + "가".repeat(300);
        assertThat(TextUtil.truncate(reason, 200)).hasSize(200).endsWith("…").startsWith("CRITICAL 알람 자동 DOWN — 가");
    }

    @Test
    @DisplayName("truncate: 서로게이트 쌍(이모지)을 반으로 자르지 않는다")
    void truncate_서로게이트() {
        String text = "ab" + "😀".repeat(5); // 😀 = UTF-16 2단위
        String cut = TextUtil.truncate(text, 5); // "ab" + 😀(2) = 4, 마지막은 말줄임표 자리
        assertThat(cut).hasSizeLessThanOrEqualTo(5).endsWith("…");
        assertThat(cut.chars().filter(c -> Character.isHighSurrogate((char) c)).count())
                .isEqualTo(cut.chars().filter(c -> Character.isLowSurrogate((char) c)).count());
    }

    @Test
    @DisplayName("LogSanitizer: 개행·제어문자·유니코드 줄바꿈을 공백으로, 길이 제한")
    void log_sanitize() {
        String forged = "정상 사유\n2026-10-06 ERROR 가짜 로그 라인\r\n\t\u0000 끝";
        String cleaned = LogSanitizer.clean(forged);
        assertThat(cleaned).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t")
                .doesNotContain("\u0000").doesNotContain(" ");
        assertThat(cleaned).contains("정상 사유").contains("가짜 로그 라인");

        assertThat(LogSanitizer.clean(null)).isEqualTo("null");
        assertThat(LogSanitizer.clean("x".repeat(1000))).hasSize(200).endsWith("…");
    }

    @Test
    @DisplayName("PageableUtil.ignoreSort: 클라이언트 sort를 버리고 page/size만 유지")
    void ignore_sort() {
        Pageable requested = PageRequest.of(2, 15, Sort.by("foo").descending());
        Pageable fixed = PageableUtil.ignoreSort(requested);
        assertThat(fixed.getPageNumber()).isEqualTo(2);
        assertThat(fixed.getPageSize()).isEqualTo(15);
        assertThat(fixed.getSort().isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("PageableUtil.allowSort: 허용 속성만 통과, 그 밖은 400 VALIDATION_ERROR")
    void allow_sort() {
        Set<String> allowed = Set.of("code", "name");
        Pageable ok = PageRequest.of(0, 10, Sort.by("code"));
        assertThat(PageableUtil.allowSort(ok, allowed)).isSameAs(ok);
        assertThat(PageableUtil.allowSort(PageRequest.of(0, 10), allowed).getSort().isUnsorted()).isTrue();

        assertThatThrownBy(() -> PageableUtil.allowSort(PageRequest.of(0, 10, Sort.by("password")), allowed))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
