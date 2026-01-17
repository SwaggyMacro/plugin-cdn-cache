package cn.ncii.cdncache.endpoint;

import cn.ncii.cdncache.entity.RefreshLog;
import cn.ncii.cdncache.service.CdnRefreshService;
import cn.ncii.cdncache.service.CdnRefreshServiceFactory;
import cn.ncii.cdncache.service.RefreshLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdnRefreshEndpointTest {

    @Mock
    private ReactiveSettingFetcher settingFetcher;

    @Mock
    private CdnRefreshServiceFactory serviceFactory;

    @Mock
    private RefreshLogService logService;

    @Mock
    private CdnRefreshService refreshService;

    @Test
    void refreshReturnsTaskIdFromResult() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode basicNode = mapper.createObjectNode().put("enabled", true);
        ObjectNode providersNode = mapper.createObjectNode();
        var providersArray = providersNode.putArray("cdnProviders");
        providersArray.addObject()
                .put("enabled", true)
                .put("provider", "TENCENT")
                .put("accessKeyId", "test-id")
                .put("accessKeySecret", "test-secret");
        providersArray.addObject()
                .put("enabled", true)
                .put("provider", "ALIYUN")
                .put("accessKeyId", "test-id-2")
                .put("accessKeySecret", "test-secret-2");

        when(settingFetcher.get("basic")).thenReturn(Mono.just(basicNode));
        when(settingFetcher.get("providers")).thenReturn(Mono.just(providersNode));
        when(settingFetcher.get("routes")).thenReturn(Mono.just(mapper.createObjectNode()));
        when(settingFetcher.get("refresh")).thenReturn(Mono.just(mapper.createObjectNode()));
        when(serviceFactory.createService(any())).thenReturn(refreshService);
        when(refreshService.refreshUrls(anyList()))
                .thenReturn(Mono.just(CdnRefreshService.RefreshResult.success("task-123")))
                .thenReturn(Mono.just(CdnRefreshService.RefreshResult.success("task-456")));
        when(logService.saveLog(any(), any(), any(), any(), anyList(), anyBoolean(), any(), any(), any()))
                .thenReturn(Mono.just(new RefreshLog()));

        CdnRefreshEndpoint endpoint = new CdnRefreshEndpoint(settingFetcher, serviceFactory, logService);
        WebTestClient client = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();

        client.post()
                .uri("/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"urls\":[\"https://example.com\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.taskId").isEqualTo("task-123, task-456");
    }
}
