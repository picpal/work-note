#!/usr/bin/env bash
# Playwright webServer 커맨드.
# 매 실행마다 새 DB로 bootJar를 server 모드로 기동한다 (2FA 유예·부트스트랩 멱등성이 DB 상태에 물려 있어 초기화가 필수).
set -euo pipefail

E2E_DIR="$(cd "$(dirname "$0")/.." && pwd)"           # frontend/e2e
FRONTEND_DIR="$(dirname "$E2E_DIR")"                   # frontend
JAR="$FRONTEND_DIR/../backend/build/libs/worknote-0.1.0.jar"
PORT="${E2E_PORT:-8331}"
# 포트별 격리: 같은 레포에서 여러 e2e 실행이 병렬로 돌아도 DB를 서로 밟지 않는다
RUNTIME="$E2E_DIR/.runtime-$PORT"

if [ ! -f "$JAR" ]; then
  echo "[e2e] bootJar 없음: $JAR" >&2
  echo "[e2e] 먼저 실행: pnpm e2e:build  (frontend dist 빌드 + bootJar)" >&2
  exit 1
fi

# 새 DB — server 모드는 DB 절대 경로 + 부모 디렉토리 700을 요구한다
rm -rf "$RUNTIME"
mkdir -p "$RUNTIME"
chmod 700 "$RUNTIME"

# WORKNOTE_2FA_KEY: Base64 32바이트(테스트 고정 키). 미설정이어도 기동은 되지만
# 2FA 등록·Redmine 토큰 저장 시나리오가 죽으므로 넣어 둔다.
exec env \
  WORKNOTE_MODE=server \
  WORKNOTE_ADMIN_PASSWORD="e2e-admin-pass-1234" \
  WORKNOTE_DB="$RUNTIME/wn.db" \
  WORKNOTE_UPLOAD_DIR="$RUNTIME/uploads" \
  WORKNOTE_2FA_KEY="$(printf 'e2e-fixed-32byte-key-0123456789A' | base64)" \
  SERVER_PORT="$PORT" \
  java -jar "$JAR"
