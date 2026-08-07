package com.fabwatch.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 API 공통 응답 래퍼 — { content, totalElements, totalPages, number } (docs/06 목록 공통 규칙).
 * 배열을 그대로 반환하지 않는다. 페이징이 없는 전체 조회도 단일 페이지로 감싼다.
 */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int number) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber());
    }

    /** 페이징이 없는 전체 목록(예: 라인 트리)을 단일 페이지 형태로 감싼다. */
    public static <T> PageResponse<T> ofAll(List<T> content) {
        return new PageResponse<>(content, content.size(), content.isEmpty() ? 0 : 1, 0);
    }
}
