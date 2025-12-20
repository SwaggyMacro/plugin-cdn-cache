package cn.ncii.cdncache.service.impl;

import cn.ncii.cdncache.CdnSetting;
import cn.ncii.cdncache.service.CdnRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 腾讯云 EdgeOne 缓存刷新服务实现
 */
@Slf4j
public class TencentEdgeOneRefreshService implements CdnRefreshService {

    private static final String HOST = "teo.tencentcloudapi.com";
    private static final String SERVICE = "teo";
    private static final String VERSION = "2022-09-01";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    private final WebClient webClient;
    private final CdnSetting setting;

    public TencentEdgeOneRefreshService(CdnSetting setting) {
        this.setting = setting;
        this.webClient = WebClient.builder()
                .baseUrl("https://" + HOST)
                .build();
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }
        // EdgeOne 使用 CreatePurgeTask 接口
        String payload = buildPurgePayload(urls, "purge_url");
        return doRefresh("CreatePurgeTask", payload);
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return Mono.just(RefreshResult.failure("目录列表为空"));
        }
        String payload = buildPurgePayload(directories, "purge_prefix");
        return doRefresh("CreatePurgeTask", payload);
    }

    private String buildPurgePayload(List<String> targets, String type) {
        // EdgeOne 需要 ZoneId，从 siteDomain 配置中获取或单独配置
        String zoneId = setting.getZoneId();
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ZoneId\":\"").append(escapeJson(zoneId)).append("\",");
        sb.append("\"Type\":\"").append(type).append("\",");
        sb.append("\"Targets\":[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(targets.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Mono<RefreshResult> doRefresh(String action, String payload) {
        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String date = getUtcDate(timestamp);

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

            String credentialScope = date + "/" + SERVICE + "/tc3_request";
            String hashedCanonicalRequest = sha256Hex(canonicalRequest);
            String stringToSign = ALGORITHM + "\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + hashedCanonicalRequest;

            byte[] secretDate = hmac256(("TC3" + setting.getAccessKeySecret()).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmac256(secretDate, SERVICE);
            byte[] secretSigning = hmac256(secretService, "tc3_request");
            String signature = bytesToHex(hmac256(secretSigning, stringToSign));

            String authorization = ALGORITHM
                    + " Credential=" + setting.getAccessKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            log.info("EdgeOne 刷新请求: action={}, payload={}", action, payload);

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
                        log.info("EdgeOne 响应: {}", response);
                        if (response.contains("\"JobId\"") && !response.contains("\"Error\"")) {
                            String jobId = extractValue(response, "JobId");
                            return RefreshResult.success(jobId);
                        } else {
                            return RefreshResult.failure("刷新失败: " + response);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("EdgeOne 刷新异常", e);
                        return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                    });

        } catch (Exception e) {
            log.error("构建 EdgeOne 请求失败", e);
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
