# FabWatch 웹 이미지: 프론트 빌드(node) → Caddy 이미지에 dist + Caddyfile 포함 (docs/13 §4)
# 빌드 컨텍스트는 저장소 루트다 (deploy/Caddyfile 을 함께 넣기 위해):
#   docker build -f frontend/Dockerfile -t fabwatch-web .
# 한 이미지에 정적 파일과 프록시 설정이 같이 들어가므로, 이미지 태그 하나를 되돌리면 화면과 프록시가 함께 롤백된다.

# ---- 1단계: 프론트 빌드 ----
FROM node:22-alpine AS build
WORKDIR /app

# 의존성 레이어 캐시 (소스만 바뀌면 npm ci 재사용)
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./

# 같은 도메인 프록시 구조이므로 API 는 상대경로로 호출한다 (SSE 도 같은 base 사용).
# VITE_ 값은 번들에 그대로 박힌다 → 공개 가능한 값만 넣는다. 데모 로그인 버튼은 기본 꺼짐.
# VITE_DEMO_PASSWORD 는 시크릿이 아니라 "공개 데모 계정(ENGINEER/TECHNICIAN)의 공개 비밀번호"다(운영 ADMIN 비밀번호와 다르게).
# 비어 있으면 운영 빌드는 VITE_DEMO_LOGIN=true 여도 데모 로그인을 켜지 않으며, ADMIN 버튼은 어떤 경우에도 번들에 들어가지 않는다.
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_DEMO_LOGIN=false
ARG VITE_DEMO_PASSWORD=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_DEMO_LOGIN=${VITE_DEMO_LOGIN} \
    VITE_DEMO_PASSWORD=${VITE_DEMO_PASSWORD}

# tsc -b && vite build
RUN npm run build

# ---- 2단계: Caddy (정적 서빙 + 리버스 프록시) ----
FROM caddy:2.11-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv

EXPOSE 80 443 443/udp
