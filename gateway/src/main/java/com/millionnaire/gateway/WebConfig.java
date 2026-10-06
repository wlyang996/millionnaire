package com.millionnaire.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * H5 客户端与联机测试台可能不在同一站点：/api 允许跨域（只用 Authorization 头里的令牌，不用 Cookie）。
 * /dev 是联机测试台（static/dev/index.html）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("GET", "POST")
                .allowedHeaders("Authorization", "Content-Type").allowCredentials(false);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/dev", "/dev/index.html");
        registry.addRedirectViewController("/dev/", "/dev/index.html");
    }
}
