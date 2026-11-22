#!/usr/bin/env bash
# End-to-end smoke test against a running backend: customer books and pays, vendor completes, admin pays out.
# Usage: scripts/smoke.sh [http://localhost:8080]
set -euo pipefail
API="${1:-http://localhost:8080}/v1"
H=(-H "X-Tenant: carwash" -H "Content-Type: application/json")
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
login() { curl -sf "${H[@]}" -X POST "$API/auth/verify" -d "{\"phone\":\"$1\",\"code\":\"123456\",\"name\":\"${2:-Smoke}\"}" | j "d['accessToken']"; }

CUST=$(login "+9198$RANDOM$RANDOM" "Smoke Test")
OWNER=$(login "+919000000001")
ADMIN=$(login "+910000000001")
echo "logged in: customer, vendor owner, admin"

LOC=$(curl -sf "${H[@]}" "$API/locations?lat=12.97&lng=77.6" | j "d[0]['id'] + ' ' + str(len(d)) + ' vendors nearby'")
LOC_ID=${LOC%% *}; echo "nearest outlet: $LOC"
DETAIL=$(curl -sf "${H[@]}" "$API/locations/$LOC_ID")
ITEM=$(echo "$DETAIL" | j "[i for i in d['items'] if i['name']=='Basic wash'][0]['id']")
DAY=$(python3 -c "import datetime as d; print((d.datetime.now(d.timezone(d.timedelta(hours=5,minutes=30)))+d.timedelta(days=1)).date())")
SLOT=$(curl -sf "${H[@]}" "$API/locations/$LOC_ID/availability?date=$DAY&itemIds=$ITEM" | j "[s for s in d if s['bookable']][4]['startsAt']")
echo "booking slot $SLOT"

ORDER=$(curl -sf "${H[@]}" -H "Authorization: Bearer $CUST" -H "Idempotency-Key: smoke-$RANDOM" -X POST "$API/orders" -d "{\"locationId\":\"$LOC_ID\",\"itemIds\":[\"$ITEM\"],\"variantKey\":\"SEDAN\",\"startsAt\":\"$SLOT\"}")
OID=$(echo "$ORDER" | j "d['id']"); echo "order $(echo "$ORDER" | j "d['code'] + ' ' + d['status'] + ' total ' + str(d['totalMinor']/100)")"
PAID=$(curl -sf "${H[@]}" -H "Authorization: Bearer $CUST" -X POST "$API/orders/$OID/pay" -d '{"method":"UPI"}' | j "d['status']"); echo "after payment: $PAID"
curl -sf "${H[@]}" -H "Authorization: Bearer $OWNER" -X POST "$API/orders/$OID/actions" -d '{"action":"START"}' | j "'vendor start -> ' + d['status']"
curl -sf "${H[@]}" -H "Authorization: Bearer $OWNER" -X POST "$API/orders/$OID/actions" -d '{"action":"COMPLETE"}' | j "'vendor complete -> ' + d['status']"
curl -sf "${H[@]}" -H "Authorization: Bearer $ADMIN" -X POST "$API/admin/payouts/run" -d '{}' | j "'payouts: ' + str([(p['providerName'], p['amountMinor']/100) for p in d])"
curl -sf "${H[@]}" -H "Authorization: Bearer $ADMIN" "$API/admin/dashboard" | j "'dashboard: ' + str({k: d[k] for k in ('ordersTotal','gmvMinor','commissionMinor')})"
echo "SMOKE OK"
