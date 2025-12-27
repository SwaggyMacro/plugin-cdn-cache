package cn.ncii.cdncache.service.impl;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.service.CdnRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 腾讯云 CDN 缓存刷新服务实现
 */
@Slf4j
public class TencentCdnRefreshService implements CdnRefreshService {

    private static final String HOST = "cdn.tencentcloudapi.com";
    private static final String SERVICE = "cdn";
    private static final String REGION = "";
    private static final String VERSION = "2018-06-06";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    private final WebClient webClient;
    private final CdnProviderConfig config;

    public TencentCdnRefreshService(CdnProviderConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl("https://" + HOST)
                .build();
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }
        return doRefresh("PurgeUrlsCache", buildUrlsPayload(urls));
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return Mono.just(RefreshResult.failure("目录列表为空"));
        }
        return doRefresh("PurgePathCache", buildPathsPayload(directories));
    }

    private String buildUrlsPayload(List<String> urls) {
        StringBuilder sb = new StringBuilder("{\"Urls\":[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(urls.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildPathsPayload(List<String> paths) {
        StringBuilder sb = new StringBuilder("{\"Paths\":[");
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(paths.get(i))).append("\"");
        }
        sb.append("],\"FlushType\":\"flush\"}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Mono<RefreshResult> doRefresh(String action, String payload) {
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String date = getUtcDate(timestamp);

            // 1. 拼接规范请求串
            String httpRequestMethod = "POST";
            String canonicalUri = "/";
            String canonicalQueryString = "";
            String contentType = "application/json; charset=utf-8";
            String canonicalHeaders = "content-type:" + contentType + "\n" + "host:" + HOST + "\n";
            String signedHeaders = "content-type;host";
            String hashedRequestPayload = sha256Hex(payload);

            String canonicalRequest = httpRequestMethod + "\n"
                    + canonicalUri + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + hashedRequestPayload;

            log.debug("CanonicalRequest:\n{}", canonicalRequest);

            // 2. 拼接待签名字符串
            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            String hashedCanonicalRequest = sha256Hex(canonicalRequest);
            String stringToSign = ALGORITHM + "\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + hashedCanonicalRequest;

            log.debug("StringToSign:\n{}", stringToSign);

            // 3. 计算签名
            byte[] secretDate = hmac256(("TC3" + config.getAccessKeySecret()).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmac256(secretDate, SERVICE);
            byte[] secretSigning = hmac256(secretService, "tc3_request");
            String signature = bytesToHex(hmac256(secretSigning, stringToSign));

            // 4. 拼接 Authorization
            String authorization = ALGORITHM
                    + " Credential=" + config.getAccessKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            log.info("腾讯云 CDN 刷新请求: action={}, urls数量={}", action, payload.length());

            return webClient.post()
                    .uri("/")
                    .header("Authorization", authorization)
                    .header("Content-Type", contentType)
                    .header("Host", HOST)
                    .header("X-TC-Action", action)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("X-TC-Version", VERSION)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(response -> {
                        log.info("腾讯云 CDN 响应: {}", response);
                        if (response.contains("\"TaskId\"") && !response.contains("\"Error\"")) {
                            String taskId = extractValue(response, "TaskId");
                            return RefreshResult.success(taskId);
                        } else {
                            return RefreshResult.failure("刷新失败: " + response);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("腾讯云 CDN 刷新异常", e);
                        return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                    });

        } catch (Exception e) {
            log.error("构建腾讯云请求失败", e);
            return Mono.just(RefreshResult.failure("构建请求失败: " + e.getMessage()));
        }
    }

    private String getUtcDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestamp * 1000));
    }

    private String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(d);
    }

    private byte[] hmac256(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String extractValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start > 0) {
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end);
            }
        }
        return "unknown";
    }
}
