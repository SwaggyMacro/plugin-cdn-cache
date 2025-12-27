package cn.ncii.cdncache.service.impl;

import cn.ncii.cdncache.entity.CdnProviderConfig;
import cn.ncii.cdncache.service.CdnRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云 ESA（边缘安全加速）缓存刷新服务实现
 * API 文档: https://help.aliyun.com/zh/edge-security-acceleration/esa/api-esa-2024-09-10-purgecaches
 * 使用 ROA 风格 API，POST + JSON body
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Slf4j
public class AliyunEsaRefreshService implements CdnRefreshService {

    private static final String ALIYUN_ESA_HOST = "esa.cn-hangzhou.aliyuncs.com";
    private static final String ALIYUN_ESA_ENDPOINT = "https://" + ALIYUN_ESA_HOST;
    private static final String API_VERSION = "2024-09-10";

    private final CdnProviderConfig config;

    public AliyunEsaRefreshService(CdnProviderConfig config) {
        this.config = config;
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }
        return doRefresh(urls, "file");
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return Mono.just(RefreshResult.failure("目录列表为空"));
        }
        return doRefresh(directories, "directory");
    }


    private Mono<RefreshResult> doRefresh(List<String> contents, String type) {
        try {
            if (config.getZoneId() == null || config.getZoneId().isBlank()) {
                return Mono.just(RefreshResult.failure("ESA 需要配置 Site ID"));
            }

            // 构建 JSON 请求体
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");
            jsonBuilder.append("\"SiteId\":").append(config.getZoneId()).append(",");
            jsonBuilder.append("\"Type\":\"").append(type).append("\",");
            jsonBuilder.append("\"Content\":{");
            
            if ("file".equals(type)) {
                jsonBuilder.append("\"Files\":[");
                for (int i = 0; i < contents.size(); i++) {
                    if (i > 0) jsonBuilder.append(",");
                    jsonBuilder.append("\"").append(escapeJson(contents.get(i))).append("\"");
                }
                jsonBuilder.append("]");
            } else {
                jsonBuilder.append("\"Directories\":[");
                for (int i = 0; i < contents.size(); i++) {
                    if (i > 0) jsonBuilder.append(",");
                    jsonBuilder.append("\"").append(escapeJson(contents.get(i))).append("\"");
                }
                jsonBuilder.append("]");
            }
            
            jsonBuilder.append("}}");
            String requestBody = jsonBuilder.toString();
            
            // 生成签名所需的时间戳和随机数
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            String nonce = UUID.randomUUID().toString();
            
            // 计算请求体的 SHA256 哈希
            String bodyHash = sha256Hex(requestBody);
            
            // 构建签名
            String signature = buildSignature(timestamp, nonce, bodyHash);
            
            log.info("阿里云 ESA 刷新请求: type={}, contents={}", type, contents);
            log.debug("阿里云 ESA 请求体: {}", requestBody);

            return WebClient.create()
                    .post()
                    .uri(ALIYUN_ESA_ENDPOINT + "/PurgeCaches")
                    .header("Host", ALIYUN_ESA_HOST)
                    .header("x-acs-action", "PurgeCaches")
                    .header("x-acs-version", API_VERSION)
                    .header("x-acs-date", timestamp)
                    .header("x-acs-signature-nonce", nonce)
                    .header("x-acs-content-sha256", bodyHash)
                    .header("Authorization", signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .exchangeToMono(response -> {
                        return response.bodyToMono(String.class)
                                .map(body -> {
                                    log.info("阿里云 ESA 响应状态: {}, 响应体: {}", response.statusCode(), body);
                                    if (response.statusCode().is2xxSuccessful() && body.contains("TaskId")) {
                                        String taskId = extractValue(body, "TaskId");
                                        return RefreshResult.success(taskId);
                                    } else {
                                        String code = extractValue(body, "Code");
                                        String message = extractValue(body, "Message");
                                        if ("unknown".equals(code)) {
                                            return RefreshResult.failure("刷新失败: " + body);
                                        }
                                        return RefreshResult.failure("刷新失败: " + code + " - " + message);
                                    }
                                });
                    })
                    .onErrorResume(e -> {
                        log.error("阿里云 ESA 刷新异常", e);
                        return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                    });
        } catch (Exception e) {
            log.error("构建阿里云 ESA 刷新请求失败", e);
            return Mono.just(RefreshResult.failure("构建请求失败: " + e.getMessage()));
        }
    }


    /**
     * 构建 ACS3 签名
     * 参考: https://help.aliyun.com/zh/sdk/product-overview/v3-request-structure-and-signature
     */
    private String buildSignature(String timestamp, String nonce, String bodyHash) throws Exception {
        String httpMethod = "POST";
        String canonicalUri = "/PurgeCaches";
        String canonicalQueryString = "";
        
        // 规范化请求头 (按字母顺序排序)
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", ALIYUN_ESA_HOST);
        headers.put("x-acs-action", "PurgeCaches");
        headers.put("x-acs-content-sha256", bodyHash);
        headers.put("x-acs-date", timestamp);
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-version", API_VERSION);
        
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            canonicalHeaders.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
            if (signedHeaders.length() > 0) {
                signedHeaders.append(";");
            }
            signedHeaders.append(entry.getKey());
        }
        
        // 构建规范化请求
        String canonicalRequest = httpMethod + "\n" +
                canonicalUri + "\n" +
                canonicalQueryString + "\n" +
                canonicalHeaders.toString() + "\n" +
                signedHeaders.toString() + "\n" +
                bodyHash;
        
        log.debug("Canonical Request:\n{}", canonicalRequest);
        
        // 计算规范化请求的哈希
        String hashedCanonicalRequest = sha256Hex(canonicalRequest);
        
        // 构建待签名字符串
        String stringToSign = "ACS3-HMAC-SHA256\n" + hashedCanonicalRequest;
        
        log.debug("String to Sign:\n{}", stringToSign);
        
        // 计算签名
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.getAccessKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signatureValue = bytesToHex(signatureBytes);
        
        // 构建 Authorization 头
        return "ACS3-HMAC-SHA256 Credential=" + config.getAccessKeyId() +
                ",SignedHeaders=" + signedHeaders.toString() +
                ",Signature=" + signatureValue;
    }

    private String sha256Hex(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start > 0) {
            start += searchKey.length();
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end);
            }
        }
        searchKey = "\"" + key + "\":";
        start = json.indexOf(searchKey);
        if (start > 0) {
            start += searchKey.length();
            int end = json.indexOf(",", start);
            int end2 = json.indexOf("}", start);
            if (end < 0 || (end2 > 0 && end2 < end)) {
                end = end2;
            }
            if (end > start) {
                return json.substring(start, end).replace("\"", "").trim();
            }
        }
        return "unknown";
    }
}
