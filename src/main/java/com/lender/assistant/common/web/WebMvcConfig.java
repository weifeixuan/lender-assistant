package com.lender.assistant.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册用户标识拦截器。
 *
 * 当前只覆盖会话相关接口；问答接口在接入多轮后由对应票追加，避免提前改变既有
 * 单轮 /api/qa 的调用契约。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserIdInterceptor userIdInterceptor;

    public WebMvcConfig(UserIdInterceptor userIdInterceptor) {
        this.userIdInterceptor = userIdInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userIdInterceptor)
                .addPathPatterns("/api/conversations/**");
    }
}
