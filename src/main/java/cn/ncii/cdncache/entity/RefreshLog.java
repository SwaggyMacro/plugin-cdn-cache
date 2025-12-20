package cn.ncii.cdncache.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;
import java.util.List;

/**
 * CDN 刷新日志实体
 *
 * @author SwaggyMacro
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "cdn-cache.halo.run", version = "v1alpha1", kind = "RefreshLog", plural = "refreshlogs", singular = "refreshlog")
public class RefreshLog extends AbstractExtension {

    @Schema(description = "日志规格")
    private RefreshLogSpec spec;

    @Data
    public static class RefreshLogSpec {

        @Schema(description = "CDN 提供商")
        private String provider;

        @Schema(description = "触发类型: POST_PUBLISH, POST_UPDATE, MANUAL")
        private String triggerType;

        @Schema(description = "关联的文章名称")
        private String postName;

        @Schema(description = "关联的文章标题")
        private String postTitle;

        @Schema(description = "刷新的 URL 列表")
        private List<String> urls;

        @Schema(description = "是否成功")
        private Boolean success;

        @Schema(description = "任务 ID")
        private String taskId;

        @Schema(description = "响应消息")
        private String message;

        @Schema(description = "请求时间")
        private Instant requestTime;

        @Schema(description = "响应时间")
        private Instant responseTime;

        @Schema(description = "耗时(毫秒)")
        private Long duration;
    }
}
