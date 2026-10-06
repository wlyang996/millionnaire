package com.millionnaire.gateway;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /** 服务器时钟：引擎从不读系统时钟，接收时间都由这里取。 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
