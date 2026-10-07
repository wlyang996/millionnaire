package com.millionnaire.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * H5 客户端与联机测试台可能不在同一站点：/api 允许跨域（只用 Authorization 头里的令牌，不用 Cookie）。
 * /dev 是联机测试台（static/dev/index.html），/admin 是游戏参数管理后台（static/admin，源码在 web-manage/）。
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
        // 服务端内部转发，不发跳转：云托管代理后面，绝对跳转地址会被拼成 https://域名:80/…
        registry.addViewController("/dev").setViewName("forward:/dev/index.html");
        registry.addViewController("/dev/").setViewName("forward:/dev/index.html");
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
