#!/usr/bin/env bash
# =====================================================================
#  IDEAQR Digital Gateway — full terminal smoke test
#  Covers the WHOLE platform end-to-end: health, auth, registration,
#  admin read APIs, role-based authorization, QR generation, object
#  lifecycle, the citizen terminal (scan / report / SOS), wallet,
#  sessions / working mode, notifications, owner-approval access,
#  complaints, guests, AND the new user-management module
#  (block / unblock / change role / reset password).
#
#  Usage:
#     # 1. start the app (default port 8080):
#     mvn spring-boot:run
#     # 2. in another terminal:
#     bash scripts/smoke-test.sh
#     # against a custom port / host:
#     BASE_URL=http://localhost:8099 bash scripts/smoke-test.sh
#
#  Demo accounts seeded by DataSeeder:
#     admin / Admin123!   doctor / Doctor123!
#     inspector / Inspect123!   citizen / Citizen123!
#
#  All destructive actions run against a throwaway account registered
#  at startup, so the demo accounts stay pristine and the script is
#  fully re-runnable.
# =====================================================================
set -u
set +B   # disable brace expansion so inline JSON bodies ({"a":"b",...}) survive intact

BASE_URL="${BASE_URL:-http://localhost:8080}"
TMP="$(mktemp -d)"
PASS=0; FAIL=0
RND="$RANDOM$RANDOM"
TESTER="tester_$RND"
TESTER_PW="Test123!"

trap 'rm -rf "$TMP"' EXIT

# ----- helpers -------------------------------------------------------

# http METHOD PATH COOKIE_JAR [JSON_BODY] -> echoes HTTP status, body in $TMP/body
http() {
  local method="$1" path="$2" jar="$3" data="${4:-}"
  if [ -n "$data" ]; then
    curl -sS -o "$TMP/body" -w '%{http_code}' -b "$jar" -c "$jar" \
      -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$data"
  else
    curl -sS -o "$TMP/body" -w '%{http_code}' -b "$jar" -c "$jar" \
      -X "$method" "$BASE_URL$path"
  fi
}

# login USER PASS JAR -> echoes HTTP status
login() {
  curl -sS -o "$TMP/body" -w '%{http_code}' -c "$3" \
    -X POST "$BASE_URL/login" \
    --data-urlencode "username=$1" --data-urlencode "password=$2"
}

# pyjson FILE DOTTED.PATH -> prints the value (supports list indexes)
pyjson() {
  python3 - "$1" "$2" <<'PY'
import sys, json
fn, path = sys.argv[1], sys.argv[2]
try:
    cur = json.load(open(fn))
except Exception:
    print(""); sys.exit(0)
for part in path.split('.'):
    if part == '':
        continue
    if isinstance(cur, list):
        try: cur = cur[int(part)]
        except Exception: print(""); sys.exit(0)
    elif isinstance(cur, dict):
        cur = cur.get(part)
    else:
        cur = None
    if cur is None:
        print(""); sys.exit(0)
print(cur)
PY
}

field() { pyjson "$TMP/body" "$1"; }

# check DESCRIPTION EXPECTED_CODE ACTUAL_CODE
check() {
  if [ "$2" = "$3" ]; then
    printf '  \033[32m✓\033[0m %s (HTTP %s)\n' "$1" "$3"; PASS=$((PASS+1))
  else
    printf '  \033[31m✗\033[0m %s — ожидался %s, получен %s\n' "$1" "$2" "$3"
    FAIL=$((FAIL+1)); printf '      body: %s\n' "$(head -c 220 "$TMP/body")"
  fi
}

section() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

ADMIN="$TMP/admin.jar"; CITIZEN="$TMP/citizen.jar"; DOCTOR="$TMP/doctor.jar"
TUSER="$TMP/tester.jar"; GUEST="$TMP/guest.jar"

printf '\033[1mIDEAQR Digital Gateway — smoke test\033[0m  →  %s\n' "$BASE_URL"

# ----- A. Health -----------------------------------------------------
section "A · Health"
check "GET /api/health" 200 "$(http GET /api/health "$TMP/anon.jar")"

# ----- B. Auth & registration ---------------------------------------
section "B · Авторизация и регистрация"
# Build the body via assignment (RHS is immune to brace expansion / word
# splitting), so the JSON object survives intact instead of being split on commas.
REG_BODY="{\"username\":\"$TESTER\",\"password\":\"$TESTER_PW\",\"firstName\":\"Тест\",\"lastName\":\"Юзер\",\"employmentStatus\":\"EMPLOYED\",\"profession\":\"SELLER\"}"
check "Регистрация нового пользователя ($TESTER)" 201 "$(http POST /api/auth/register "$TUSER" "$REG_BODY")"
check "Логин admin"     200 "$(login admin Admin123! "$ADMIN")"
check "Логин citizen"   200 "$(login citizen Citizen123! "$CITIZEN")"
check "Логин doctor"    200 "$(login doctor Doctor123! "$DOCTOR")"
check "Логин tester"    200 "$(login "$TESTER" "$TESTER_PW" "$TUSER")"
check "Неверный пароль → 401" 401 "$(login admin WRONGPASS "$TMP/bad.jar")"
check "GET /api/auth/me (admin)" 200 "$(http GET /api/auth/me "$ADMIN")"

# ----- C. Admin read APIs -------------------------------------------
section "C · Админ-панель (чтение)"
check "GET /api/admin/stats"     200 "$(http GET /api/admin/stats "$ADMIN")"
check "GET /api/admin/analytics" 200 "$(http GET /api/admin/analytics "$ADMIN")"
check "GET /api/admin/users"     200 "$(http GET /api/admin/users "$ADMIN")"
check "GET /api/admin/modules"   200 "$(http GET /api/admin/modules "$ADMIN")"
check "GET /api/admin/events"    200 "$(http GET /api/admin/events "$ADMIN")"
check "GET /api/admin/complaints" 200 "$(http GET /api/admin/complaints "$ADMIN")"

# ----- D. Authorization boundary ------------------------------------
section "D · Граница доступа (RBAC)"
check "citizen → /api/admin/users запрещён (403)" 403 "$(http GET /api/admin/users "$CITIZEN")"
check "Аноним → /api/v2/wallet запрещён (401)"   401 "$(http GET /api/v2/wallet "$TMP/anon2.jar")"

# ----- E. QR generation ---------------------------------------------
section "E · Генерация QR-кодов"
http POST /api/admin/qr/create "$ADMIN" \
  '{"category":"RETAIL","displayName":"Смоук-товар","brand":"IDEAQR","price":4990,"description":"Тестовая карточка"}' >/dev/null
OBJ="$(field objectUid)"
[ -n "$OBJ" ] && { printf '  \033[32m✓\033[0m Объект создан: %s\n' "$OBJ"; PASS=$((PASS+1)); } \
              || { printf '  \033[31m✗\033[0m Объект не создан\n'; FAIL=$((FAIL+1)); }
check "GET /api/admin/qr/list" 200 "$(http GET /api/admin/qr/list "$ADMIN")"

# ----- F. Object lifecycle ------------------------------------------
section "F · Жизненный цикл объекта (CREATED→ACTIVE→MODIFIED→ARCHIVED)"
check "modify → MODIFIED"   200 "$(http POST "/api/admin/objects/$OBJ/modify"  "$ADMIN" '{"note":"коррекция цены"}')"
check "archive → ARCHIVED"  200 "$(http POST "/api/admin/objects/$OBJ/archive" "$ADMIN" '{"note":"снят с продажи"}')"
ST="$(field details.status)"
[ "$ST" = "ARCHIVED" ] && { printf '  \033[32m✓\033[0m Статус после archive = ARCHIVED\n'; PASS=$((PASS+1)); } \
                       || { printf '  \033[31m✗\033[0m Ожидался ARCHIVED, получен "%s"\n' "$ST"; FAIL=$((FAIL+1)); }
check "activate → ACTIVE"   200 "$(http POST "/api/admin/objects/$OBJ/activate" "$ADMIN" '{}')"

# ----- G. Citizen terminal: scan / report / SOS ---------------------
section "G · Терминал гражданина (скан / обращение / SOS)"
http POST /api/v2/scan "$CITIZEN" '{"objectUid":"RETAIL_ADIDAS_SHIRT"}' >/dev/null
SCAN_OUTCOME="$(field outcome)"; SCAN_TRUST="$(field trustScore)"; SCAN_INTER="$(field interactionUid)"
[ "$SCAN_OUTCOME" = "APPROVED" ] && { printf '  \033[32m✓\033[0m Скан RETAIL_ADIDAS_SHIRT → APPROVED (TrustScore=%s)\n' "$SCAN_TRUST"; PASS=$((PASS+1)); } \
                                 || { printf '  \033[31m✗\033[0m Скан вернул "%s"\n' "$SCAN_OUTCOME"; FAIL=$((FAIL+1)); }
check "POST /api/v2/report" 200 "$(http POST /api/v2/report "$CITIZEN" '{"objectUid":"INFRA_SUBSTATION_07","message":"Повреждение ограждения"}')"
check "POST /api/v2/sos"    200 "$(http POST /api/v2/sos    "$CITIZEN" '{"message":"Тестовый SOS"}')"

# ----- H. QR Wallet --------------------------------------------------
section "H · QR Wallet"
http GET /api/v2/wallet "$CITIZEN" >/dev/null
WQR="$(field myQr.qrValue)"
[ -n "$WQR" ] && { printf '  \033[32m✓\033[0m /wallet отдаёт Мой QR (%s) + секции объектов/заявок/истории\n' "$WQR"; PASS=$((PASS+1)); } \
              || { printf '  \033[31m✗\033[0m /wallet не вернул myQr\n'; FAIL=$((FAIL+1)); }

# ----- I. Sessions / working mode -----------------------------------
section "I · Сессии и рабочий режим (временные роли)"
check "GET  /api/v2/session"       200 "$(http GET  /api/v2/session       "$DOCTOR")"
check "POST /api/v2/mode/work"     200 "$(http POST /api/v2/mode/work     "$DOCTOR" '{}')"
check "POST /api/v2/mode/personal" 200 "$(http POST /api/v2/mode/personal "$DOCTOR")"

# ----- J. Notifications ---------------------------------------------
section "J · Центр уведомлений"
check "GET /api/v2/notifications" 200 "$(http GET /api/v2/notifications "$CITIZEN")"

# ----- K. Owner-approval access + complaints ------------------------
section "K · Owner-approval доступ + жалобы"
CIT_ID="$(http GET /api/auth/me "$CITIZEN" >/dev/null; field identityUid)"
if [ -n "$CIT_ID" ]; then
  check "doctor сканирует QR личности citizen → REVIEW" 200 \
    "$(http POST /api/v2/scan "$DOCTOR" "{\"objectUid\":\"IDENTITY:$CIT_ID\"}")"
  http GET /api/v2/access/pending "$CITIZEN" >/dev/null
  PEND_INTER="$(field 0.interactionUid)"
  if [ -n "$PEND_INTER" ]; then
    check "citizen подтверждает доступ" 200 "$(http POST "/api/v2/access/$PEND_INTER/confirm" "$CITIZEN")"
  else
    printf '  \033[33m∘\033[0m Нет ожидающих запросов доступа (пропуск подтверждения)\n'
  fi
fi
if [ -n "${SCAN_INTER:-}" ]; then
  http POST /api/v2/complaints "$CITIZEN" \
    "{\"interactionUid\":\"$SCAN_INTER\",\"subject\":\"Смоук-жалоба\",\"category\":\"QUALITY\",\"description\":\"Тестовое описание\"}" >/dev/null
  CMP="$(field details.complaintUid)"
  [ -n "$CMP" ] && { printf '  \033[32m✓\033[0m Жалоба создана: %s\n' "$CMP"; PASS=$((PASS+1)); } \
                || { printf '  \033[31m✗\033[0m Жалоба не создана\n'; FAIL=$((FAIL+1)); }
  [ -n "$CMP" ] && check "admin меняет статус жалобы" 200 \
    "$(http POST "/api/admin/complaints/$CMP/status" "$ADMIN" '{"status":"IN_PROGRESS"}')"
fi

# ----- L. Guests -----------------------------------------------------
section "L · Гостевой режим"
check "POST /api/auth/guest (создание гостя)" 200 "$(http POST /api/auth/guest "$GUEST")"
check "Гость сканирует публичный объект"      200 "$(http POST /api/v2/scan "$GUEST" '{"objectUid":"ECO_SMART_BIN_102"}')"

# ----- M. USER MANAGEMENT (новый модуль) ----------------------------
section "M · Управление пользователями (БЛОКИРОВКА / РОЛИ / ПАРОЛЬ)"
# Block
check "admin блокирует tester" 200 "$(http POST "/api/admin/users/$TESTER/block" "$ADMIN" '{"reason":"smoke"}')"
check "  └ активная сессия tester → 403" 403 "$(http GET /api/v2/wallet "$TUSER")"
check "  └ блокированный tester не может войти → 401" 401 "$(login "$TESTER" "$TESTER_PW" "$TMP/blk.jar")"
# Unblock
check "admin разблокирует tester" 200 "$(http POST "/api/admin/users/$TESTER/unblock" "$ADMIN")"
check "  └ tester снова может войти → 200" 200 "$(login "$TESTER" "$TESTER_PW" "$TUSER")"
# Promote / demote
check "admin повышает tester до ADMIN" 200 "$(http POST "/api/admin/users/$TESTER/role" "$ADMIN" '{"admin":true}')"
login "$TESTER" "$TESTER_PW" "$TUSER" >/dev/null   # re-login: authorities are session-bound
check "  └ tester получает доступ к админке → 200" 200 "$(http GET /api/admin/users "$TUSER")"
check "admin понижает tester до USER" 200 "$(http POST "/api/admin/users/$TESTER/role" "$ADMIN" '{"role":"USER"}')"
login "$TESTER" "$TESTER_PW" "$TUSER" >/dev/null
check "  └ tester снова без доступа к админке → 403" 403 "$(http GET /api/admin/users "$TUSER")"
# Guard rails
check "admin не может заблокировать сам себя (422)" 422 "$(http POST /api/admin/users/admin/block "$ADMIN" '{}')"
# Reset password
http POST "/api/admin/users/$TESTER/reset-password" "$ADMIN" >/dev/null
TMP_PW="$(field details.temporaryPassword)"
[ -n "$TMP_PW" ] && { printf '  \033[32m✓\033[0m Пароль tester сброшен (временный: %s)\n' "$TMP_PW"; PASS=$((PASS+1)); } \
                 || { printf '  \033[31m✗\033[0m Сброс пароля не вернул временный пароль\n'; FAIL=$((FAIL+1)); }
[ -n "$TMP_PW" ] && check "  └ вход с временным паролем → 200" 200 "$(login "$TESTER" "$TMP_PW" "$TMP/tmp.jar")"

# ----- N. Logout -----------------------------------------------------
section "N · Выход"
check "POST /logout (admin)" 200 "$(curl -sS -o "$TMP/body" -w '%{http_code}' -b "$ADMIN" -c "$ADMIN" -X POST "$BASE_URL/logout")"

# ----- Summary -------------------------------------------------------
TOTAL=$((PASS+FAIL))
printf '\n\033[1m──────────────────────────────────────────\033[0m\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ: %s/%s ✓\033[0m\n' "$PASS" "$TOTAL"
else
  printf '\033[1;31m ОШИБКИ: %s из %s провалено\033[0m  (пройдено %s)\n' "$FAIL" "$TOTAL" "$PASS"
fi
printf '\033[1m──────────────────────────────────────────\033[0m\n'
exit "$FAIL"
