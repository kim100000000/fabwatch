package com.fabwatch.common.util;

import com.fabwatch.common.exception.BusinessException;
import com.fabwatch.common.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * 클라이언트가 보낸 Pageable의 sort 파라미터를 다루는 헬퍼.
 * 존재하지 않는 속성(?sort=foo)을 그대로 넘기면 쿼리 생성 단계에서 PropertyReferenceException → 500이 된다.
 */
public final class PageableUtil {

    private PageableUtil() {
    }

    /** 정렬이 서버 고정인 목록 — 클라이언트 sort를 무시하고 page/size만 사용한다. */
    public static Pageable ignoreSort(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * 정렬 가능한 목록 — 허용 속성만 통과시키고 그 밖의 속성은 400 VALIDATION_ERROR.
     * (Pageable 기본 정렬 @PageableDefault도 같은 검사를 거치므로 기본 정렬 속성도 허용 목록에 있어야 한다.)
     */
    public static Pageable allowSort(Pageable pageable, Set<String> allowedProperties) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowedProperties.contains(order.getProperty())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "정렬할 수 없는 항목입니다. 허용: " + String.join(", ", new java.util.TreeSet<>(allowedProperties)));
            }
        }
        return pageable;
    }
}
