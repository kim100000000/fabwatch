package com.fabwatch.sensor.service;

import com.fabwatch.sensor.dto.SensorSeriesResponse;
import com.fabwatch.sensor.entity.SensorData1m;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 1분 집계 → 더 큰 버킷(5분/1시간) 다운샘플링 (docs/06 §3, 안정성 감사 M-7).
 *
 * 조회 기간이 길어 센서당 포인트가 상한을 넘을 때 서버가 버킷을 키워 응답 크기를 제한한다.
 * 합성 규칙: min=버킷 내 최솟값, max=최댓값, 평균=표본 수 가중 평균(sampleCount), sampleCount=합계.
 * 버킷은 UTC 에포크 기준으로 정렬한다(예: 5분 버킷은 00,05,10…분).
 * 입력은 시간 오름차순이어야 한다. 순수 함수라 단위 테스트한다.
 */
public final class SeriesDownsampler {

    /** 선택 가능한 버킷(분) 후보 — 작은 것부터 시도한다 (1M → 5M → 1H) */
    static final int[] BUCKET_MINUTES = {1, 5, 60};

    private SeriesDownsampler() {
    }

    /**
     * 구간(분)과 포인트 상한으로 가장 작은 버킷(분)을 고른다.
     *
     * @return 1 / 5 / 60. 1시간 버킷으로도 상한을 넘으면 -1 (호출 측이 400 처리)
     */
    public static int chooseBucketMinutes(long rangeMinutes, int maxPoints) {
        for (int bucket : BUCKET_MINUTES) {
            // 정렬 때문에 양 끝 버킷이 걸쳐 최대 +1개
            if (rangeMinutes / bucket + 1 <= maxPoints) {
                return bucket;
            }
        }
        return -1;
    }

    /** 응답 granularity 문자열 (1→"1M", 5→"5M", 60→"1H") */
    public static String granularityOf(int bucketMinutes) {
        return switch (bucketMinutes) {
            case 5 -> SensorSeriesResponse.FIVE_MINUTES;
            case 60 -> SensorSeriesResponse.ONE_HOUR;
            default -> SensorSeriesResponse.ONE_MINUTE;
        };
    }

    /** 1분 집계 행을 포인트로 변환한다. bucketMinutes가 1이면 변환만, 그보다 크면 합성한다. */
    public static List<SensorSeriesResponse.Point> toPoints(List<SensorData1m> rows, int bucketMinutes) {
        List<SensorSeriesResponse.Point> points = new ArrayList<>();
        if (bucketMinutes <= 1) {
            for (SensorData1m row : rows) {
                points.add(new SensorSeriesResponse.Point(
                        row.getBucketAt(), row.getAvgV(), row.getMinV(), row.getMaxV(), row.getSampleCount()));
            }
            return points;
        }
        long bucketSeconds = bucketMinutes * 60L;
        long currentKey = Long.MIN_VALUE;
        BigDecimal min = null;
        BigDecimal max = null;
        BigDecimal weightedSum = BigDecimal.ZERO;
        long count = 0;
        for (SensorData1m row : rows) {
            long key = Math.floorDiv(row.getBucketAt().getEpochSecond(), bucketSeconds);
            if (key != currentKey) {
                if (currentKey != Long.MIN_VALUE) {
                    points.add(point(currentKey * bucketSeconds, min, max, weightedSum, count));
                }
                currentKey = key;
                min = null;
                max = null;
                weightedSum = BigDecimal.ZERO;
                count = 0;
            }
            min = smaller(min, row.getMinV());
            max = larger(max, row.getMaxV());
            long weight = row.getSampleCount() == null || row.getSampleCount() <= 0 ? 1 : row.getSampleCount();
            if (row.getAvgV() != null) {
                weightedSum = weightedSum.add(row.getAvgV().multiply(BigDecimal.valueOf(weight)));
                count += weight;
            }
        }
        if (currentKey != Long.MIN_VALUE) {
            points.add(point(currentKey * bucketSeconds, min, max, weightedSum, count));
        }
        return points;
    }

    private static SensorSeriesResponse.Point point(long epochSecond, BigDecimal min, BigDecimal max,
                                                    BigDecimal weightedSum, long count) {
        BigDecimal avg = count == 0 ? null : weightedSum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new SensorSeriesResponse.Point(Instant.ofEpochSecond(epochSecond), avg, min, max,
                count == 0 ? null : (int) Math.min(count, Integer.MAX_VALUE));
    }

    private static BigDecimal smaller(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static BigDecimal larger(BigDecimal current, BigDecimal candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }
}
