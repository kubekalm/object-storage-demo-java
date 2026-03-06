#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL=${API_BASE_URL:-}
TENANT_ID=${TENANT_ID:-}
APP_ID=${APP_ID:-}
DEPLOY_TOKEN=${DEPLOY_TOKEN:-}
IMAGE=${IMAGE:-}

if [ -z "$IMAGE" ]; then
  echo "IMAGE is required" >&2
  exit 1
fi
if [ -z "$API_BASE_URL" ] || [ -z "$TENANT_ID" ] || [ -z "$APP_ID" ] || [ -z "$DEPLOY_TOKEN" ]; then
  echo "API_BASE_URL, TENANT_ID, APP_ID, and DEPLOY_TOKEN are required" >&2
  exit 1
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }
}
for cmd in curl jq; do
  require_cmd "$cmd"
done

BASE_API_URL="${API_BASE_URL%/}"
DEPLOY_ENDPOINT="${BASE_API_URL}/tenants/${TENANT_ID}/apps/${APP_ID}/deploy"
PAYLOAD=$(jq -nc --arg image "$IMAGE" '{image:$image}')

echo "Triggering deploy for app ${APP_ID}"
curl -fsS -X POST "$DEPLOY_ENDPOINT" \
  -H "Authorization: Bearer ${DEPLOY_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD" >/dev/null

echo "Deploy request accepted for image: ${IMAGE}"
