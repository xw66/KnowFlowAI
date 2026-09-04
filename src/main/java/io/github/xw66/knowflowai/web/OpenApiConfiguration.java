package io.github.xw66.knowflowai.web;

import java.util.List;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OpenApiConfiguration {
    @Bean
    OpenAPI knowFlowApi() {
        return new OpenAPI().info(new Info().title("KnowFlow AI 企业知识平台").version("0.1")
                .description("先注册登录，再在 Authorize 中填写 accessToken；权限以当前知识库成员关系为准。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    OpenApiCustomizer anonymousAuthEndpoints() {
        return api -> {
            for (String path : List.of("/api/auth/register", "/api/auth/login")) {
                var item = api.getPaths().get(path);
                if (item != null && item.getPost() != null) item.getPost().setSecurity(List.of());
            }
        };
    }
}
