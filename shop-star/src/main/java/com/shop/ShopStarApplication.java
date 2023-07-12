package com.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.shop.mapper")
@SpringBootApplication
// 暴露代理对象
@EnableAspectJAutoProxy(exposeProxy = true)
public class ShopStarApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopStarApplication.class, args);
    }

}
