package com.fabwatch.aireport.service;

import com.fabwatch.aireport.config.AiProperties;
import com.fabwatch.aireport.entity.AiCallLog;
import com.fabwatch.aireport.repository.AiCallLogRepository;
import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 일일 쿼터 (docs/03 F-6.4: 기본 전체 20건 + 사용자별 5건, 초과 시 403 AI_QUOTA_EXCEEDED — 데모 비용 보호).
 *
 * 두 한도가 모두 적용된다. 전체 한도(AI_DAILY_QUOTA)는 모든 사용자 합산, 개인 한도(AI_USER_DAILY_QUOTA)는 userId별 —
 * 공개 데모 계정 한 명이 전체 한도를 독점하지 못하게 한다. 같은 403 코드를 쓰고 message로 둘을 구분한다(전체를 먼저 검사).
 *
 * 집계 방식: ai_call_logs의 오늘(KST) 행 수 — 성공/실패/진행 중/mock 모두 1건으로 센다(두 한도 같은 기준).
 * 개인 집계는 기존 인덱스(requested_at) 범위 스캔 후 user_id로 거르며, 하루치 행이 최대 전체 한도(기본 20)라 별도 인덱스가 필요 없다.
 * 요청이 수락되는 시점에 로그 행을 먼저 만들기(reserve) 때문에 동시 진행 중인 요청도 한도에 반영된다.
 * 호출 측(AiReportService)이 검사~기록을 하나의 락+트랜잭션으로 묶어 경합을 막는다(단일 인스턴스 전제).
 */
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiCallLogRepository callLogRepository;
    private final AiProperties props;
    private final Clock aiClock;

    /** 경계 판정 — 사용량이 한도 이상이면 초과(N-1 허용, N 거부). */
    public static boolean isExceeded(long used, int quota) {
        return used >= quota;
    }

    /**
     * 쿼터를 확인하고 호출 이력 1행을 기록한다. 한도 초과면 아무것도 기록하지 않고 403.
     * 반드시 트랜잭션 안에서 호출한다.
     */
    public AiCallLog reserve(Long reportId, Long userId) {
        Instant now = aiClock.instant();
        KstDayRange today = KstDayRange.of(now);
        long used = callLogRepository.countByRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                today.from(), today.to());
        if (isExceeded(used, props.dailyQuota())) {
            throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED,
                    "오늘의 AI 리포트 전체 일일 한도(" + props.dailyQuota() + "건)를 초과했습니다. "
                            + "내일 다시 시도하거나 수동으로 작성하세요.");
        }
        if (userId != null) {
            long usedByUser = callLogRepository.countByUserIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                    userId, today.from(), today.to());
            if (isExceeded(usedByUser, props.userDailyQuota())) {
                throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED,
                        "오늘의 AI 리포트 개인 일일 한도(" + props.userDailyQuota() + "건)를 초과했습니다. "
                                + "내일 다시 시도하거나 수동으로 작성하세요.");
            }
        }
        String provider = props.normalizedProvider();
        String model = props.isMock() ? "mock" : props.model();
        return callLogRepository.save(new AiCallLog(reportId, userId, now, provider, model));
    }
}
