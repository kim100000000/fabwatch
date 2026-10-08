package com.fabwatch.sensor.service;

import com.fabwatch.sensor.dto.SensorSeriesResponse;
import com.fabwatch.sensor.entity.SensorData1m;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 1분 집계 다운샘플링 순수 로직 (안정성 감사 M-7) */
class SeriesDownsamplerTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private static SensorData1m row(int minuteOffset, String min, String max, String avg, Integer count) {
        return new SensorData1m(1L, BASE.plusSeconds(minuteOffset * 60L),
                min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                avg == null ? null : new BigDecimal(avg), count);
    }

    @Test
    @DisplayName("버킷 선택: 포인트 상한 안이면 1분, 넘으면 5분 → 1시간, 1시간으로도 넘으면 -1")
    void 버킷_선택() {
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 7, 20_000)).isEqualTo(1);      // 7일 = 10,080
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 13, 20_000)).isEqualTo(1);     // 13일 = 18,720
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 14, 20_000)).isEqualTo(5);     // 14일 = 20,160 > 20,000
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 31, 20_000)).isEqualTo(5);     // 31일 → 8,929
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 365, 20_000)).isEqualTo(60);   // 1년 → 8,761
        assertThat(SeriesDownsampler.chooseBucketMinutes(60 * 24 * 365 * 10, 20_000)).isEqualTo(-1);
        assertThat(SeriesDownsampler.chooseBucketMinutes(100, 100)).as("경계 정렬로 +1개").isEqualTo(5);
    }

    @Test
    @DisplayName("granularity 문자열: 1→1M, 5→5M, 60→1H")
    void granularity_문자열() {
        assertThat(SeriesDownsampler.granularityOf(1)).isEqualTo("1M");
        assertThat(SeriesDownsampler.granularityOf(5)).isEqualTo("5M");
        assertThat(SeriesDownsampler.granularityOf(60)).isEqualTo("1H");
    }

    @Test
    @DisplayName("1분 버킷은 변환만 한다 (값 그대로)")
    void 일분은_변환만() {
        List<SensorSeriesResponse.Point> points = SeriesDownsampler.toPoints(
                List.of(row(0, "1.00", "3.00", "2.00", 30)), 1);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).at()).isEqualTo(BASE);
        assertThat(points.get(0).value()).isEqualByComparingTo("2.00");
        assertThat(points.get(0).minValue()).isEqualByComparingTo("1.00");
        assertThat(points.get(0).maxValue()).isEqualByComparingTo("3.00");
        assertThat(points.get(0).sampleCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("5분 합성: min/max는 극값, 평균은 표본 수 가중 평균, 표본 수는 합계, 시각은 버킷 시작")
    void 오분_합성() {
        List<SensorData1m> rows = List.of(
                row(0, "1.00", "5.00", "2.00", 10),   // 가중합 20
                row(1, "0.50", "4.00", "4.00", 30),   // 가중합 120
                row(4, "2.00", "9.00", "3.00", 20));  // 가중합 60 → (200)/60 = 3.33
        List<SensorSeriesResponse.Point> points = SeriesDownsampler.toPoints(rows, 5);

        assertThat(points).hasSize(1);
        SensorSeriesResponse.Point p = points.get(0);
        assertThat(p.at()).isEqualTo(BASE);
        assertThat(p.minValue()).isEqualByComparingTo("0.50");
        assertThat(p.maxValue()).isEqualByComparingTo("9.00");
        assertThat(p.value()).isEqualByComparingTo("3.33");
        assertThat(p.sampleCount()).isEqualTo(60);
    }

    @Test
    @DisplayName("버킷 경계: 5분 경계(00:05)를 넘으면 새 버킷, 빈 구간은 포인트를 만들지 않는다")
    void 버킷_경계와_빈구간() {
        List<SensorData1m> rows = List.of(
                row(4, "1.00", "1.00", "1.00", 1),
                row(5, "2.00", "2.00", "2.00", 1),
                row(17, "3.00", "3.00", "3.00", 1)); // 10~14 구간 비어 있음, 15 버킷으로 정렬
        List<SensorSeriesResponse.Point> points = SeriesDownsampler.toPoints(rows, 5);

        assertThat(points).extracting(SensorSeriesResponse.Point::at)
                .containsExactly(BASE, BASE.plusSeconds(300), BASE.plusSeconds(900));
    }

    @Test
    @DisplayName("1시간 합성: 1시간 경계 정렬, 표본 수 null은 가중치 1로 취급")
    void 한시간_합성과_null_표본수() {
        List<SensorData1m> rows = new ArrayList<>();
        rows.add(row(0, "1.00", "1.00", "1.00", null));
        rows.add(row(30, "3.00", "3.00", "3.00", null));
        rows.add(row(60, "5.00", "5.00", "5.00", 10));
        List<SensorSeriesResponse.Point> points = SeriesDownsampler.toPoints(rows, 60);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).value()).isEqualByComparingTo("2.00");
        assertThat(points.get(0).sampleCount()).isEqualTo(2);
        assertThat(points.get(1).at()).isEqualTo(BASE.plusSeconds(3600));
    }

    @Test
    @DisplayName("빈 입력은 빈 결과")
    void 빈_입력() {
        assertThat(SeriesDownsampler.toPoints(List.of(), 5)).isEmpty();
        assertThat(SeriesDownsampler.toPoints(List.of(), 1)).isEmpty();
    }
}
