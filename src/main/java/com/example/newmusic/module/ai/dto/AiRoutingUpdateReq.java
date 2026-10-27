package com.example.newmusic.module.ai.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.Map;

/** 场景 → 模型档位（SMALL / MEDIUM / LARGE）。未知场景或档位会被拒绝，避免把错字写进配置。 */
@Data
public class AiRoutingUpdateReq {

    @NotEmpty(message = "路由配置不能为空")
    private Map<String, String> routing;
}
