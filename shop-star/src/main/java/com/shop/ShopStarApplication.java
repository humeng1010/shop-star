package com.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.shop.mapper")
@SpringBootApplication
public class ShopStarApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopStarApplication.class, args);
    }

}
