package com.fabwatch.common.util;

import java.util.Map;

/**
 * 이름 조회 맵에서 null 키를 안전하게 다루기 위한 헬퍼.
 *
 * 배경: UserQueryService.findNamesByIds 등은 대상이 없으면 {@code Map.of()}(불변 맵)를 돌려주는데,
 * 불변 맵의 get(null)은 NPE를 던진다. 시스템이 만든 이력(자동 DOWN의 changed_by, 미확인 알람의 ack_by 등)은
 * 사용자 ID가 null이라 목록 API가 통째로 500이 되는 사고가 난다. 그 지점을 이 한 곳으로 모은다.
 */
public final class Lookup {

    private Lookup() {
    }

    public static <K, V> V get(Map<K, V> map, K key) {
        return (map == null || key == null) ? null : map.get(key);
    }
}
