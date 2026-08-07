package com.fabwatch.auth.entity;

/**
 * 사용자 역할 (docs/11 §3 권한 매트릭스).
 * TECHNICIAN을 MAINTENANCE로 바꾸지 않는다 — 역할=사람, Maintenance는 업무 용어 (CLAUDE.md).
 */
public enum Role {
    ADMIN,
    ENGINEER,
    TECHNICIAN
}
