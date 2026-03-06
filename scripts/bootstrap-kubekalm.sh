#!/usr/bin/env bash
set -euo pipefail

CRED_FILE=${CRED_FILE:-}
TENANT_ID=${TENANT_ID:-}
IMAGE=${IMAGE:-}
APP_PORT=${APP_PORT:-8080}
APP_SIZE=${APP_SIZE:-xsmall}
RUN_ID=${RUN_ID:-$(date -u +%y%m%d%H%M%S)}
APP_NAME=${APP_NAME:-objstore-docs-demo-${RUN_ID}}
ADDON_NAME=${ADDON_NAME:-objstore-docs-${RUN_ID}}
DEPLOY_TOKEN_NAME=${DEPLOY_TOKEN_NAME:-github-main}
SECRETS_DIR=${SECRETS_DIR:-./.local-secrets/object-storage-docs-demo}

if [ -z "$IMAGE" ]; then
  echo "IMAGE is required" >&2
  exit 1
fi
if [ -z "$CRED_FILE" ] || [ -z "$TENANT_ID" ]; then
  echo "CRED_FILE and TENANT_ID are required" >&2
  exit 1
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}
for cmd in awk curl jq mkdir chmod; do
  require_cmd "$cmd"
done

if [ ! -f "$CRED_FILE" ]; then
  echo "Credentials file not found: $CRED_FILE" >&2
  exit 1
fi

LOGIN_URL=$(awk -F': ' '/^api url:/{print $2}' "$CRED_FILE")
EMAIL=$(awk -F': ' '/^email:/{print $2}' "$CRED_FILE")
PASSWORD=$(awk -F': ' '/^password:/{print $2}' "$CRED_FILE")
BASE_API_URL="${LOGIN_URL%/auth/login}"
BASE_API_URL="${BASE_API_URL%/}"

if [ -z "$LOGIN_URL" ] || [ -z "$EMAIL" ] || [ -z "$PASSWORD" ]; then
  echo "Missing email/password/api url in $CRED_FILE" >&2
  exit 1
fi

log() {
  printf '%s %s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$*"
}

LOGIN_PAYLOAD=$(jq -nc --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')
ACCESS_TOKEN=$(curl -sS -X POST -H 'Content-Type: application/json' -d "$LOGIN_PAYLOAD" "$LOGIN_URL" | jq -r '.accessToken // empty')
if [ -z "$ACCESS_TOKEN" ]; then
  echo "Unable to login (MFA may be enabled for this account)" >&2
  exit 1
fi

api_request() {
  local method="$1"
  local path="$2"
  local payload="${3:-}"
  if [ -n "$payload" ]; then
    curl -sS -X "$method" \
      -H "Authorization: Bearer ${ACCESS_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "$payload" \
      "${BASE_API_URL}${path}"
  else
    curl -sS -X "$method" \
      -H "Authorization: Bearer ${ACCESS_TOKEN}" \
      "${BASE_API_URL}${path}"
  fi
}

log "Creating app: ${APP_NAME}"
APP_CREATE_PAYLOAD=$(jq -nc \
  --arg name "$APP_NAME" \
  --arg image "$IMAGE" \
  --arg size "$APP_SIZE" \
  --argjson port "$APP_PORT" \
  '{name:$name,image:$image,port:$port,size:$size,replicas:1,domain:"",pathPrefix:"/",ingress:{enabled:true},env:[],secrets:[],registryCredentialIds:[]}')
APP_RESP=$(api_request "POST" "/tenants/${TENANT_ID}/apps" "$APP_CREATE_PAYLOAD")
APP_ID=$(printf '%s' "$APP_RESP" | jq -r '.id // empty')
if [ -z "$APP_ID" ]; then
  echo "Failed to create app: $APP_RESP" >&2
  exit 1
fi

log "Creating object-storage addon: ${ADDON_NAME}"
ADDON_CREATE_PAYLOAD=$(jq -nc --arg name "$ADDON_NAME" '{type:"object-storage",plan:"shared",name:$name}')
ADDON_RESP=$(api_request "POST" "/tenants/${TENANT_ID}/addons" "$ADDON_CREATE_PAYLOAD")
ADDON_ID=$(printf '%s' "$ADDON_RESP" | jq -r '.id // empty')
if [ -z "$ADDON_ID" ]; then
  echo "Failed to create object-storage addon: $ADDON_RESP" >&2
  exit 1
fi

log "Waiting for addon to become ready"
for _ in $(seq 1 80); do
  ADDON_STATE=$(api_request "GET" "/tenants/${TENANT_ID}/addons/${ADDON_ID}" "")
  STATUS=$(printf '%s' "$ADDON_STATE" | jq -r '.status // empty')
  if [ "$STATUS" = "ready" ]; then
    break
  fi
  if [ "$STATUS" = "failed" ]; then
    echo "Addon provisioning failed: $ADDON_STATE" >&2
    exit 1
  fi
  sleep 5
done

if [ "${STATUS:-}" != "ready" ]; then
  echo "Addon did not become ready in time" >&2
  exit 1
fi

log "Binding addon to app"
BIND_PAYLOAD=$(jq -nc --arg addonId "$ADDON_ID" '{addonId:$addonId}')
BIND_RESP=$(api_request "POST" "/tenants/${TENANT_ID}/apps/${APP_ID}/addons" "$BIND_PAYLOAD")
BINDING_ID=$(printf '%s' "$BIND_RESP" | jq -r '.id // empty')
if [ -z "$BINDING_ID" ]; then
  echo "Failed to bind addon to app: $BIND_RESP" >&2
  exit 1
fi

log "Creating deploy token: ${DEPLOY_TOKEN_NAME}"
TOKEN_PAYLOAD=$(jq -nc --arg name "$DEPLOY_TOKEN_NAME" '{name:$name}')
TOKEN_RESP=$(api_request "POST" "/tenants/${TENANT_ID}/apps/${APP_ID}/deploy-tokens" "$TOKEN_PAYLOAD")
DEPLOY_TOKEN=$(printf '%s' "$TOKEN_RESP" | jq -r '.token // empty')
DEPLOY_TOKEN_ID=$(printf '%s' "$TOKEN_RESP" | jq -r '.id // empty')
if [ -z "$DEPLOY_TOKEN" ] || [ -z "$DEPLOY_TOKEN_ID" ]; then
  echo "Failed to create deploy token: $TOKEN_RESP" >&2
  exit 1
fi

log "Triggering deployment through deploy token"
DEPLOY_PAYLOAD=$(jq -nc --arg image "$IMAGE" '{image:$image}')
curl -fsS -X POST "${BASE_API_URL}/tenants/${TENANT_ID}/apps/${APP_ID}/deploy" \
  -H "Authorization: Bearer ${DEPLOY_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "$DEPLOY_PAYLOAD" >/dev/null

log "Waiting for app readiness"
APP_URL=""
for _ in $(seq 1 100); do
  APP_STATE=$(api_request "GET" "/tenants/${TENANT_ID}/apps/${APP_ID}" "")
  READY=$(printf '%s' "$APP_STATE" | jq -r '.status.ready // false')
  URL=$(printf '%s' "$APP_STATE" | jq -r '.status.url // empty')
  if [ "$READY" = "true" ] && [ -n "$URL" ]; then
    APP_URL="$URL"
    break
  fi
  sleep 5
done

if [ -z "$APP_URL" ]; then
  echo "App did not become ready in time" >&2
  exit 1
fi
if [[ "$APP_URL" == http://* ]]; then
  APP_URL="https://${APP_URL#http://}"
fi

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"
OUT_FILE="${SECRETS_DIR}/${RUN_ID}-bootstrap.env"
cat >"$OUT_FILE" <<EOF
KUBEKALM_API_BASE_URL=${BASE_API_URL}
KUBEKALM_TENANT_ID=${TENANT_ID}
KUBEKALM_APP_ID=${APP_ID}
KUBEKALM_APP_URL=${APP_URL}
KUBEKALM_DEPLOY_TOKEN=${DEPLOY_TOKEN}
KUBEKALM_DEPLOY_TOKEN_ID=${DEPLOY_TOKEN_ID}
KUBEKALM_ADDON_ID=${ADDON_ID}
KUBEKALM_APP_NAME=${APP_NAME}
KUBEKALM_ADDON_NAME=${ADDON_NAME}
EOF
chmod 600 "$OUT_FILE"

cat <<EOF
Bootstrap completed.
App ID: ${APP_ID}
Addon ID: ${ADDON_ID}
App URL: ${APP_URL}
Deploy Token ID: ${DEPLOY_TOKEN_ID}
Deploy token saved at: ${OUT_FILE}
EOF
