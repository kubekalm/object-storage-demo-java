# Object Storage Demo (Java / Spring Boot)

This is a developer tutorial project showing how to build and deploy a simple document manager app on KubeKALM using an **object-storage add-on**.

The UI is intentionally storage-agnostic: end users see documents, not S3 terminology.

## 1) What You Build

A minimal web app with:
- Upload document (`max 5MB`)
- List documents
- Download document
- Delete document

Backend API:
- `GET /api/health`
- `GET /api/documents`
- `POST /api/documents` (`multipart/form-data`, field `file`)
- `GET /api/documents/{id}/download`
- `DELETE /api/documents/{id}`

## 2) Prerequisites

- Docker with buildx
- Access to `registry.kubekalm.io`
- KubeKALM control-plane credentials file
- `curl`, `jq`, `awk`

For local run (optional):
- Java 21
- Gradle or wrapper-equivalent environment

## 3) How The App Connects To Object Storage

When you bind an object-storage add-on to the app, KubeKALM injects env vars into the pod:

- `OBJECT_STORAGE_ACCESS_KEY_ID`
- `OBJECT_STORAGE_SECRET_ACCESS_KEY`
- `OBJECT_STORAGE_ENDPOINT`
- `OBJECT_STORAGE_BUCKET`
- `OBJECT_STORAGE_REGION`
- `OBJECT_STORAGE_FORCE_PATH_STYLE`

Compatibility fallbacks are also supported:
- `S3_*`
- `AWS_*` credential/region keys

## 4) Run Locally

```bash
cd '/work/object-storage-demo-java'
gradle --no-daemon bootRun
```

Open:
- `http://localhost:8080`

If object-storage env vars are missing locally, the app still starts, but document operations return a storage-not-configured error.

## 5) Build And Push Image

```bash
cd '/work/object-storage-demo-java'
TAG=$(date -u +%y%m%d%H%M)
IMAGE="registry.kubekalm.io/system/object-storage-docs-demo:${TAG}"
docker buildx build --platform 'linux/amd64' --push -t "${IMAGE}" '.'
echo "${IMAGE}"
```

## 6) One-Time Bootstrap In KubeKALM

This script creates and wires everything:
- application
- object-storage add-on
- app <-> add-on binding
- deploy token for CI/CD
- initial deploy

```bash
cd '/work/object-storage-demo-java'
CRED_FILE='<path-to-cp-credentials-file>' \
TENANT_ID='<tenant-id>' \
IMAGE='registry.kubekalm.io/system/object-storage-docs-demo:<TAG>' \
./scripts/bootstrap-kubekalm.sh
```

The script stores generated deployment secrets in:
- `./.local-secrets/object-storage-docs-demo/<run-id>-bootstrap.env`

That file contains:
- `KUBEKALM_API_BASE_URL`
- `KUBEKALM_TENANT_ID`
- `KUBEKALM_APP_ID`
- `KUBEKALM_APP_URL`
- `KUBEKALM_DEPLOY_TOKEN`

## 7) Deploy New Image Manually (Existing App)

```bash
cd '/work/object-storage-demo-java'
API_BASE_URL='https://<your-console-host>/api' \
TENANT_ID='<tenant-id>' \
APP_ID='<app-id>' \
DEPLOY_TOKEN='<id.secret>' \
IMAGE='registry.kubekalm.io/system/object-storage-docs-demo:<TAG>' \
./scripts/deploy-kubekalm.sh
```

## 8) GitHub CI/CD Tutorial (Auto Deploy On `main`)

Workflow file:
- `'.github/workflows/ci-cd.yml'`

Pipeline flow:
1. Run tests and build jar
2. Build/push Docker image (`linux/amd64`)
3. Trigger KubeKALM deploy endpoint with deploy token

Create these repository secrets:
- `REGISTRY_USERNAME`
- `REGISTRY_PASSWORD`
- `KUBEKALM_API_BASE_URL`
- `KUBEKALM_TENANT_ID`
- `KUBEKALM_APP_ID`
- `KUBEKALM_DEPLOY_TOKEN`

You can source KubeKALM values from your bootstrap output file under:
- `./.local-secrets/object-storage-docs-demo/`

## 9) Validate End-To-End

Basic API checks:

```bash
APP_URL='https://<your-app>.apps.kubekalm.io'
curl -sS "${APP_URL}/api/health"
curl -sS "${APP_URL}/api/documents"
```

For full browser flow verification (upload/list/download/delete), use Playwright or your own E2E tool against the deployed URL.

## 10) Code Tour

- `src/main/java/com/kubekalm/sprintdemo/service/S3DocumentStorageService.java`
  - S3-compatible client setup and document CRUD operations.
- `src/main/java/com/kubekalm/sprintdemo/controller/DocumentApiController.java`
  - REST API + error mapping for document actions.
- `src/main/resources/templates/index.html`
  - Single-page document manager UI shell.
- `src/main/resources/static/js/app.js`
  - Frontend behavior for upload/list/download/delete.
- `src/main/resources/static/css/app.css`
  - Visual styling and responsive layout.
- `scripts/bootstrap-kubekalm.sh`
  - First-time platform setup automation.
- `scripts/deploy-kubekalm.sh`
  - Deploy trigger helper for existing app.
