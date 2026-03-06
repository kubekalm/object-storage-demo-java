# Object Storage Add-on Java Developer Tutorial

This repository is a practical tutorial for integrating a Java/Spring Boot app with KubeKALM Object Storage.

You get:
- Concept explanation (how the add-on works)
- Connection/authentication model
- Java SDK snippets (init, upload, list, download, delete)
- Full demo app you can build and deploy

## 1. Core Concepts

KubeKALM Object Storage is S3-compatible but multi-tenant safe by design.

- Your app talks to an S3-compatible endpoint exposed by KubeKALM.
- Credentials are issued per add-on binding.
- The platform enforces tenant/add-on isolation through internal prefixing.
- Your app should treat it like a regular bucket + key model.

What your app needs to know:
- Endpoint
- Access key / secret key
- Region
- Bucket
- Path-style flag

What your app does not need:
- Internal backend bucket details
- Internal prefix logic

## 2. Credentials And Auth Model

When you bind an object-storage add-on to an app, env vars are injected into the app pod:

- `OBJECT_STORAGE_ENDPOINT`
- `OBJECT_STORAGE_ACCESS_KEY_ID`
- `OBJECT_STORAGE_SECRET_ACCESS_KEY`
- `OBJECT_STORAGE_REGION`
- `OBJECT_STORAGE_BUCKET`
- `OBJECT_STORAGE_FORCE_PATH_STYLE`

The demo also supports compatibility fallbacks:
- `S3_*`
- `AWS_*` (credentials + region)

Authentication is standard S3 Signature V4 (handled by AWS SDK).

## 3. Connect To The S3 URL (Java)

Minimal AWS SDK v2 client setup used by this project:

```java
S3Client client = S3Client.builder()
    .region(Region.of(region))
    .endpointOverride(URI.create(endpoint))
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKeyId, secretAccessKey)
    ))
    .serviceConfiguration(S3Configuration.builder()
        .pathStyleAccessEnabled(forcePathStyle)
        .chunkedEncodingEnabled(false)
        .checksumValidationEnabled(false)
        .build())
    .build();
```

Notes:
- `pathStyleAccessEnabled(true)` is important for many S3-compatible gateways.
- `chunkedEncodingEnabled(false)` helps compatibility with some non-AWS backends.

## 4. Java Code Snippets

Upload:

```java
client.putObject(
    PutObjectRequest.builder()
        .bucket(bucket)
        .key("documents/manual.pdf")
        .contentType("application/pdf")
        .build(),
    RequestBody.fromBytes(bytes)
);
```

List:

```java
ListObjectsV2Response out = client.listObjectsV2(
    ListObjectsV2Request.builder()
        .bucket(bucket)
        .prefix("documents/")
        .build()
);
for (S3Object obj : out.contents()) {
    System.out.println(obj.key() + " (" + obj.size() + ")");
}
```

Download:

```java
ResponseBytes<GetObjectResponse> file = client.getObjectAsBytes(
    GetObjectRequest.builder()
        .bucket(bucket)
        .key("documents/manual.pdf")
        .build()
);
byte[] bytes = file.asByteArray();
```

Delete:

```java
client.deleteObject(
    DeleteObjectRequest.builder()
        .bucket(bucket)
        .key("documents/manual.pdf")
        .build()
);
```

## 5. Demo App Architecture

- `DocumentApiController`: REST endpoints for upload/list/download/delete
- `S3DocumentStorageService`: S3 client + document operations
- `index.html` + `app.js` + `app.css`: lightweight document manager UI

The UI intentionally says "document" everywhere and hides storage internals.

## 6. Run Locally

```bash
cd '/work/object-storage-demo-java'
gradle --no-daemon bootRun
```

Open `http://localhost:8080`.

If storage env vars are missing, read operations work with clear errors but no uploads will succeed.

## 7. Build And Push

```bash
cd '/work/object-storage-demo-java'
TAG=$(date -u +%y%m%d%H%M)
IMAGE="registry.kubekalm.io/system/object-storage-docs-demo:${TAG}"
docker buildx build --platform 'linux/amd64' --push -t "${IMAGE}" '.'
echo "${IMAGE}"
```

## 8. Bootstrap On KubeKALM (One Time)

This script creates:
- app
- object-storage add-on
- app/add-on binding
- deploy token
- initial deploy

```bash
cd '/work/object-storage-demo-java'
CRED_FILE='<path-to-cp-credentials-file>' \
TENANT_ID='<tenant-id>' \
IMAGE='registry.kubekalm.io/system/object-storage-docs-demo:<TAG>' \
./scripts/bootstrap-kubekalm.sh
```

Generated deployment values are written to:
- `./.local-secrets/object-storage-docs-demo/<run-id>-bootstrap.env`

## 9. Deploy Existing App

```bash
cd '/work/object-storage-demo-java'
API_BASE_URL='https://<your-console-host>/api' \
TENANT_ID='<tenant-id>' \
APP_ID='<app-id>' \
DEPLOY_TOKEN='<id.secret>' \
IMAGE='registry.kubekalm.io/system/object-storage-docs-demo:<TAG>' \
./scripts/deploy-kubekalm.sh
```

## 10. GitHub CI/CD (Auto Deploy On `main`)

Workflow:
- `.github/workflows/ci-cd.yml`

Required secrets:
- `REGISTRY_USERNAME`
- `REGISTRY_PASSWORD`
- `KUBEKALM_API_BASE_URL`
- `KUBEKALM_TENANT_ID`
- `KUBEKALM_APP_ID`
- `KUBEKALM_DEPLOY_TOKEN`

Flow:
1. Build/test
2. Build/push image
3. Trigger `/deploy` with deploy token

## 11. Quick Validation Commands

```bash
APP_URL='https://<your-app>.apps.kubekalm.io'
curl -sS "${APP_URL}/api/health"
curl -sS "${APP_URL}/api/documents"
```

For full browser validation, run an E2E script (Playwright/Selenium/etc.) that performs upload/list/download/delete.
