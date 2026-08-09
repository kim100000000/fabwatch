package com.fabwatch.equipment.service;

import com.fabwatch.common.event.EquipmentDownRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * CRITICAL 알람 → 설비 자동 DOWN (docs/03 F-4.3).
 *
 * alarm 도메인이 EquipmentDownRequestedEvent를 발행하고 equipment 도메인이 여기서 받는다.
 * 이 경계면 덕분에 alarm은 Equipment 엔티티·레포지토리·상태 머신을 전혀 모른다
 * (CLAUDE.md "도메인 간 직접 참조 금지").
 *
 * ★ 안전 게이트(docs/11 §10): 결과는 DB 필드 변경 + 상태 로그뿐. 실설비 물리 제어가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentAutoDownListener {

    private final EquipmentService equipmentService;

    @EventListener
    public void onDownRequested(EquipmentDownRequestedEvent event) {
        equipmentService.autoDown(event.equipmentId(), event.reason(), event.requestedAt());
    }
}
