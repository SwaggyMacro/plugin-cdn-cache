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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云 CDN 缓存刷新服务实现
 * API 文档: https://help.aliyun.com/zh/cdn/developer-reference/api-cdn-2018-05-10-refreshobjectcaches
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Slf4j
public class AliyunCdnRefreshService implements CdnRefreshService {

    private static final String ALIYUN_CDN_ENDPOINT = "https://cdn.aliyuncs.com";
    private static final String API_VERSION = "2018-05-10";

    private final WebClient webClient;
    private final CdnProviderConfig config;

    public AliyunCdnRefreshService(CdnProviderConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl(ALIYUN_CDN_ENDPOINT)
                .build();
    }

    @Override
    public Mono<RefreshResult> refreshUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return Mono.just(RefreshResult.failure("URL 列表为空"));
        }

        // 多个 URL 用换行符分隔
        String objectPath = String.join("\n", urls);
        return doRefresh(objectPath, "File");
    }

    @Override
    public Mono<RefreshResult> refreshDirectories(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return Mono.just(RefreshResult.failure("目录列表为空"));
        }

        String objectPath = String.join("\n", directories);
        return doRefresh(objectPath, "Directory");
    }

    private Mono<RefreshResult> doRefresh(String objectPath, String objectType) {
        try {
            // 构建请求参数
            Map<String, String> params = new TreeMap<>();
            
            // 公共参数
            params.put("Format", "JSON");
            params.put("Version", API_VERSION);
            params.put("AccessKeyId", config.getAccessKeyId());
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureVersion", "1.0");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            
            // UTC 时间 ISO 8601 格式
            String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            params.put("Timestamp", timestamp);
            
            // 业务参数
            params.put("Action", "RefreshObjectCaches");
            params.put("ObjectPath", objectPath);
            params.put("ObjectType", objectType);

            // 生成签名
            String signature = sign(params, config.getAccessKeySecret());
            params.put("Signature", signature);

            // 构建完整 URL
            StringBuilder urlBuilder = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (urlBuilder.length() > 0) {
                    urlBuilder.append("&");
                }
                urlBuilder.append(specialUrlEncode(entry.getKey()))
                        .append("=")
                        .append(specialUrlEncode(entry.getValue()));
            }
            
            String requestUrl = "?" + urlBuilder.toString();
            String fullUrl = ALIYUN_CDN_ENDPOINT + requestUrl;
            log.info("阿里云 CDN 刷新请求: objectType={}, urls={}", objectType, objectPath);
            log.info("阿里云 CDN Timestamp: {}", timestamp);
            log.info("阿里云 CDN 完整请求 URL: {}", fullUrl);

            // 使用新的 WebClient 直接请求完整 URL，避免二次编码
            return WebClient.create()
                    .get()
                    .uri(java.net.URI.create(fullUrl))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        return response.bodyToMono(String.class)
                                .map(body -> {
                                    log.info("阿里云 CDN 响应状态: {}, 响应体: {}", response.statusCode(), body);
                                    if (response.statusCode().is2xxSuccessful() && body.contains("RefreshTaskId")) {
                                        String taskId = extractValue(body, "RefreshTaskId");
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
                        log.error("阿里云 CDN 刷新异常", e);
                        return Mono.just(RefreshResult.failure("刷新异常: " + e.getMessage()));
                    });
        } catch (Exception e) {
            log.error("构建阿里云 CDN 刷新请求失败", e);
            return Mono.just(RefreshResult.failure("构建请求失败: " + e.getMessage()));
        }
    }

    /**
     * 生成签名
     * 参考: https://help.aliyun.com/zh/sdk/product-overview/rpc-mechanism
     */
    private String sign(Map<String, String> params, String accessKeySecret) throws Exception {
        // 1. 构造规范化请求字符串
        StringBuilder canonicalizedQueryString = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (canonicalizedQueryString.length() > 0) {
                canonicalizedQueryString.append("&");
            }
            canonicalizedQueryString.append(specialUrlEncode(entry.getKey()))
                    .append("=")
                    .append(specialUrlEncode(entry.getValue()));
        }

        // 2. 构造待签名字符串
        String stringToSign = "GET" + "&" + 
                specialUrlEncode("/") + "&" + 
                specialUrlEncode(canonicalizedQueryString.toString());
        
        log.debug("StringToSign: {}", stringToSign);

        // 3. 计算签名 (HMAC-SHA1)
        String key = accessKeySecret + "&";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        
        return Base64.getEncoder().encodeToString(signData);
    }

    /**
     * 特殊 URL 编码
     * 阿里云要求: 空格编码为 %20，星号编码为 %2A，波浪号不编码
     */
    private String specialUrlEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 从 JSON 响应中提取值
     */
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
        // 尝试不带引号的值（数字等）
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
