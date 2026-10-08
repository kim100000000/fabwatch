package com.fabwatch.sensor.controller;

import com.fabwatch.common.security.SecurityUtils;
import com.fabwatch.sensor.service.SensorStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 스트림 (docs/06 §3): GET /stream/sensors?token={accessToken}&amp;equipmentId={id}
 *
 * 인증: EventSource가 커스텀 헤더를 못 붙이므로 **이 엔드포인트에 한해** 쿼리 파라미터 token을 허용한다
 * (docs/11 §4). 검증은 JwtAuthenticationFilter가 연결 시 1회 수행한다. 만료 시 연결이 끊기고
 * 프론트가 재연결하면 재검증된다.
 *
 * 토큰 노출 완화책은 다음 두 가지뿐이다 — 별도의 마스킹 필터는 두지 않았다:
 *   ① 쿼리 토큰 허용 경로를 /api/v1/stream/ 접두사로 한정 (JwtAuthenticationFilter.SSE_PATH_PREFIX)
 *   ② 애플리케이션 로그에는 쿼리스트링이 빠진 request.getRequestURI()만 남긴다 (docs/11 P-5)
 * ⚠ 잔여 위험: 톰캣 액세스 로그(server.tomcat.accesslog.enabled)를 켜면 기본 패턴이 쿼리스트링을
 *   포함하므로 token이 파일 로그로 샌다. 활성화 시 pattern에서 쿼리스트링을 제외할 것.
 *
 * equipmentId 생략 → 전체 라인 구독(메인 대시보드용).
 *
 * 연결 상한: 사용자당 5개(초과 시 가장 오래된 연결을 닫고 새 연결 허용), 전체 200개(초과 시 429 RATE_LIMITED, JSON 에러 본문).
 */
@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class SensorStreamController {

    private final SensorStreamService sensorStreamService;

    /**
     * Content-Type에 charset=UTF-8을 명시한다 — payload에 ℃·한글 알람 메시지가 들어가고,
     * SseEmitter 기본 응답은 charset이 비어 있어 중간 프록시가 latin-1로 오해할 여지를 남기기 때문.
     */
    @GetMapping("/sensors")
    public ResponseEntity<SseEmitter> streamSensors(@RequestParam(required = false) Long equipmentId) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, java.nio.charset.StandardCharsets.UTF_8))
                .body(sensorStreamService.subscribe(equipmentId, SecurityUtils.currentUserId()));
    }
}
