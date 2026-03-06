package com.kubekalm.sprintdemo.service;

import com.kubekalm.sprintdemo.model.DocumentDownload;
import com.kubekalm.sprintdemo.model.DocumentItem;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class S3DocumentStorageService {
    private static final long MAX_UPLOAD_BYTES = 5L * 1024L * 1024L;
    private static final String KEY_PREFIX = "documents/";
    private static final String DEFAULT_REGION = "kk-global-1";

    private volatile S3Client s3Client;
    private final String bucket;
    private final boolean configured;
    private final String configurationError;
    private final String endpoint;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final boolean forcePathStyle;

    public S3DocumentStorageService(Environment env) {
        String endpointValue = envFirst(env, "OBJECT_STORAGE_ENDPOINT", "S3_ENDPOINT");
        String bucketValue = envFirst(env, "OBJECT_STORAGE_BUCKET", "S3_BUCKET");
        String regionValue = envFirst(env, "OBJECT_STORAGE_REGION", "AWS_REGION");
        String accessKeyIdValue = envFirst(env, "OBJECT_STORAGE_ACCESS_KEY_ID", "AWS_ACCESS_KEY_ID");
        String secretAccessKeyValue = envFirst(env, "OBJECT_STORAGE_SECRET_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY");
        boolean forcePathStyleValue = parseBool(envFirst(env, "OBJECT_STORAGE_FORCE_PATH_STYLE", "S3_FORCE_PATH_STYLE"), true);

        if (isBlank(endpointValue) || isBlank(bucketValue) || isBlank(accessKeyIdValue) || isBlank(secretAccessKeyValue)) {
            this.s3Client = null;
            this.bucket = "";
            this.configured = false;
            this.configurationError = "Object storage credentials are not configured for this app.";
            this.endpoint = "";
            this.region = DEFAULT_REGION;
            this.accessKeyId = "";
            this.secretAccessKey = "";
            this.forcePathStyle = true;
            return;
        }

        String resolvedRegion = isBlank(regionValue) ? DEFAULT_REGION : regionValue.trim();
        this.bucket = bucketValue.trim();
        this.endpoint = endpointValue.trim();
        this.region = resolvedRegion;
        this.accessKeyId = accessKeyIdValue.trim();
        this.secretAccessKey = secretAccessKeyValue.trim();
        this.forcePathStyle = forcePathStyleValue;
        this.configured = true;
        this.configurationError = "";
        this.s3Client = null;
    }

    public boolean isConfigured() {
        return configured;
    }

    public String configurationError() {
        return configurationError;
    }

    public List<DocumentItem> listDocuments() {
        requireConfigured();
        List<DocumentItem> out = new ArrayList<>();
        String continuationToken = null;

        do {
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(KEY_PREFIX)
                    .continuationToken(continuationToken)
                    .maxKeys(1000)
                    .build();
            ListObjectsV2Response res = client().listObjectsV2(req);
            for (S3Object object : res.contents()) {
                String key = object.key();
                if (isBlank(key) || key.endsWith("/")) {
                    continue;
                }
                out.add(new DocumentItem(
                        encodeId(key),
                        displayNameFromKey(key),
                        Optional.ofNullable(object.size()).orElse(0L),
                        Optional.ofNullable(object.lastModified()).orElse(Instant.EPOCH)));
            }
            continuationToken = res.isTruncated() ? res.nextContinuationToken() : null;
        } while (!isBlank(continuationToken));

        out.sort(Comparator.comparing(DocumentItem::uploadedAt).reversed());
        return out;
    }

    public DocumentItem uploadDocument(MultipartFile file) {
        requireConfigured();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a file to upload.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("File size exceeds 5MB limit.");
        }

        String safeName = sanitizeFileName(file.getOriginalFilename());
        String objectKey = KEY_PREFIX + Instant.now().toEpochMilli() + "-" + randomToken() + "__" + safeName;

        String contentType = Optional.ofNullable(file.getContentType())
                .map(String::trim)
                .filter((value) -> !value.isEmpty())
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try {
            byte[] bytes = file.getBytes();
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .metadata(Map.of("document-name", safeName))
                    .build();
            client().putObject(req, RequestBody.fromBytes(bytes));
            return new DocumentItem(encodeId(objectKey), safeName, bytes.length, Instant.now());
        } catch (Exception ex) {
            throw new RuntimeException("Unable to upload document.", ex);
        }
    }

    public DocumentDownload downloadDocument(String id) {
        requireConfigured();
        String key = decodeAndValidateId(id);
        try {
            GetObjectRequest req = GetObjectRequest.builder().bucket(bucket).key(key).build();
            ResponseBytes<GetObjectResponse> out = client().getObjectAsBytes(req);
            String contentType = Optional.ofNullable(out.response().contentType())
                    .map(String::trim)
                    .filter((value) -> !value.isEmpty())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            return new DocumentDownload(displayNameFromKey(key), contentType, out.asByteArray());
        } catch (Exception ex) {
            throw new RuntimeException("Unable to download document.", ex);
        }
    }

    public void deleteDocument(String id) {
        requireConfigured();
        String key = decodeAndValidateId(id);
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ex) {
            throw new RuntimeException("Unable to delete document.", ex);
        }
    }

    private void requireConfigured() {
        if (!configured) {
            throw new IllegalStateException(configurationError);
        }
    }

    private S3Client client() {
        requireConfigured();
        if (s3Client != null) {
            return s3Client;
        }
        synchronized (this) {
            if (s3Client == null) {
                s3Client = S3Client.builder()
                        .region(Region.of(region))
                        .endpointOverride(URI.create(withScheme(endpoint)))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                        .serviceConfiguration(S3Configuration.builder()
                                .pathStyleAccessEnabled(forcePathStyle)
                                .chunkedEncodingEnabled(false)
                                .checksumValidationEnabled(false)
                                .build())
                        .build();
            }
        }
        return s3Client;
    }

    private static String decodeAndValidateId(String encodedId) {
        if (isBlank(encodedId)) {
            throw new IllegalArgumentException("Document ID is required.");
        }
        try {
            String key = new String(Base64.getUrlDecoder().decode(encodedId), StandardCharsets.UTF_8);
            if (!key.startsWith(KEY_PREFIX) || key.contains("..") || key.contains("\\")) {
                throw new IllegalArgumentException("Invalid document ID.");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid document ID.");
        }
    }

    private static String encodeId(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String displayNameFromKey(String key) {
        if (isBlank(key)) {
            return "document.bin";
        }
        int marker = key.lastIndexOf("__");
        if (marker >= 0 && marker + 2 < key.length()) {
            return key.substring(marker + 2);
        }
        int slash = key.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < key.length()) {
            return key.substring(slash + 1);
        }
        return key;
    }

    private static String sanitizeFileName(String raw) {
        String fileName = Optional.ofNullable(raw).orElse("document.bin").trim();
        if (fileName.isEmpty()) {
            fileName = "document.bin";
        }
        String normalized = fileName.replaceAll("[^A-Za-z0-9._-]", "-");
        while (normalized.contains("--")) {
            normalized = normalized.replace("--", "-");
        }
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.isEmpty()) {
            return "document.bin";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String randomToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String withScheme(String endpoint) {
        String value = endpoint.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return "https://" + value;
    }

    private static String envFirst(Environment env, String... keys) {
        for (String key : keys) {
            String value = env.getProperty(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean parseBool(String value, boolean defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
