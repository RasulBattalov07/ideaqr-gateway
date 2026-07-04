#!/usr/bin/env bash
# =====================================================================
#  IDEAQR Digital Gateway — 4-device end-to-end simulation
#
#  Simulates 4 independent physical devices via 4 separate cookie jars
#  (admin / doctor / patient / new-citizen). Each device primes the
#  double-submit XSRF token, authenticates, then scans every demo object
#  and the digital business-card identity.
#
#  CRITICAL CONTROL: the run FAILS (non-zero exit) if any prepared demo
#  entity resolves to "Объект не найден в реестре" (OBJECT_NOT_FOUND).
#  Governance verdicts (APPROVED / REJECTED by role, working mode, hours,
#  consent) and cross-tenant identity misses are expected and do NOT fail.
#
#  Usage:
#     mvn spring-boot:run                       # terminal 1
#     bash scripts/e2e-4device.sh               # terminal 2
#     BASE_URL=http://host:8099 bash scripts/e2e-4device.sh
# =====================================================================
set -u
BASE="${BASE_URL:-http://localhost:8080}"
JD="$(mktemp -d)"; BODY="$JD/body.json"
trap 'rm -rf "$JD"' EXIT

ADMIN="$JD/cookie_admin.txt"     # device 1 — admin (unscoped, cross-tenant)
DOCTOR="$JD/cookie_doctor.txt"   # device 2 — doctor «Санжар Ким» (hospital tenant)
PATIENT="$JD/cookie_patient.txt" # device 3 — patient «Айдос» (public tenant)
GUEST="$JD/cookie_guest.txt"     # device 4 — brand-new citizen (registered live)
RND="$RANDOM$RANDOM"; NEWCIT="grazhdanin_$RND"; NEWPW="Grazhdanin2026!"
AIDOS_QR="IDENTITY:aaaaaaaa-0000-0000-0000-000000000007"

notfound=0; scans=0
tok(){ awk '$6=="XSRF-TOKEN"{v=$7} END{print v}' "$1" 2>/dev/null; }
prime(){ curl -sS -o /dev/null -c "$1" "$BASE/api/health"; }
jget(){ python3 - "$1" "$2" <<'PY'
import sys,json
try: d=json.load(open(sys.argv[1]))
except Exception: d={}
cur=d
for p in sys.argv[2].split('.'):
    cur = cur.get(p) if isinstance(cur,dict) else None
    if cur is None: break
print('' if cur is None else cur)
PY
}
login(){ prime "$3"; curl -sS -o "$BODY" -w '%{http_code}' -b "$3" -c "$3" \
  -H "X-XSRF-TOKEN: $(tok "$3")" -X POST "$BASE/login" \
  --data-urlencode "username=$1" --data-urlencode "password=$2"; }
register(){ prime "$3"; local b="{\"username\":\"$1\",\"password\":\"$2\",\"firstName\":\"Нурлан\",\"lastName\":\"Жаксыбеков\",\"employmentStatus\":\"UNEMPLOYED\",\"profession\":\"CITIZEN\"}"
  curl -sS -o "$BODY" -w '%{http_code}' -b "$3" -c "$3" -H "X-XSRF-TOKEN: $(tok "$3")" \
  -H 'Content-Type: application/json' -X POST "$BASE/api/auth/register" -d "$b"; }
scan(){ curl -sS -o "$BODY" -w '%{http_code}' -b "$1" -c "$1" -H "X-XSRF-TOKEN: $(tok "$1")" \
  -H 'Content-Type: application/json' -X POST "$BASE/api/v2/scan" -d "{\"objectUid\":\"$2\"}"; }
me_uid(){ curl -sS -o "$BODY" -b "$1" "$BASE/api/auth/me" >/dev/null; jget "$BODY" identityUid; }

row(){ local label="$1" jar="$2" obj="$3" code oc rsn flag
  code=$(scan "$jar" "$obj"); oc=$(jget "$BODY" outcome); rsn=$(jget "$BODY" reason)
  scans=$((scans+1)); flag="ok"
  if printf '%s' "$rsn" | grep -q "не найден в реестре"; then flag="NOT-FOUND"; notfound=$((notfound+1)); fi
  if printf '%s' "$rsn" | grep -q "не найдена в системе"; then flag="ident-miss(tenant)"; fi
  printf '  %-9s %-46s HTTP %-3s %-9s %s\n' "$label" "$obj" "$code" "${oc:-—}" "$flag"
}

echo "════════════════════════════════════════════════════════════════════════"
echo " 4-DEVICE E2E  →  $BASE"
echo "════════════════════════════════════════════════════════════════════════"
echo "· Login 4 devices"
printf '  admin(Аружан)      login    → HTTP %s\n' "$(login admin Admin123! "$ADMIN")"
printf '  doctor(Санжар Ким) login    → HTTP %s\n' "$(login doctor Doctor123! "$DOCTOR")"
printf '  patient(Айдос)     login    → HTTP %s\n' "$(login aidos Aidos123! "$PATIENT")"
printf '  citizen(%s) register → HTTP %s\n' "$NEWCIT" "$(register "$NEWCIT" "$NEWPW" "$GUEST")"
printf '  citizen(%s) login    → HTTP %s\n' "$NEWCIT" "$(login "$NEWCIT" "$NEWPW" "$GUEST")"

AID=$(me_uid "$PATIENT"); DOC=$(me_uid "$DOCTOR")
echo "· identityUid: Айдос=$AID  Санжар=$DOC"
echo
echo "· CRITICAL CONTROL — every device scans every demo entity"
echo "  ---------------------------------------------------------------------"
for pair in "admin:$ADMIN" "doctor:$DOCTOR" "patient:$PATIENT" "citizen:$GUEST"; do
  for obj in RETAIL_NIKE_AF1 MED_RX_5521 SERVICE_TRASH_PICKUP CAR_TOYOTA_CAMRY LOCK_OFFICE_AITU DOC_STUDENT_AITU "$AIDOS_QR"; do
    row "${pair%%:*}" "${pair#*:}" "$obj"
  done
  echo "  ---------------------------------------------------------------------"
done
echo
echo "· P2P — devices scan each other's identity QR"
row "citizen→" "$GUEST"  "IDENTITY:$AID"   # new citizen → Aidos (public)   → REVIEW
row "doctor→"  "$DOCTOR" "IDENTITY:$AID"   # doctor → Aidos (public)        → REVIEW
row "admin→"   "$ADMIN"  "IDENTITY:$DOC"   # admin (unscoped) → doctor      → REVIEW
row "citizen→" "$GUEST"  "IDENTITY:$DOC"   # citizen → doctor (cross-tenant)→ ident-miss (expected)
echo
echo "════════════════════════════════════════════════════════════════════════"
if [ "$notfound" -eq 0 ]; then
  printf ' RESULT: \033[32mPASS\033[0m — %s scans, 0 "не найден в реестре" errors\n' "$scans"
else
  printf ' RESULT: \033[31mFAIL\033[0m — %s scans, %s "не найден в реестре" errors\n' "$scans" "$notfound"
fi
echo "════════════════════════════════════════════════════════════════════════"
exit "$notfound"
