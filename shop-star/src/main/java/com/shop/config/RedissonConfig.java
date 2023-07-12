package com.shop.config;

import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties("spring.redis")
public class RedissonConfig {

    private String host;
    private String port;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config
                .useSingleServer()
                .setAddress(String.format("redis://%s:%s", host, port));

        return Redisson.create(config);

    }
}
