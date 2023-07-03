package com.shop.utils;

import cn.hutool.jwt.JWT;
import org.springframework.util.StringUtils;

import java.util.Date;

public class JwtHelper {
    // token的有效时间
    private static long tokenExpiration = 24 * 60 * 60 * 60 * 1000;
    // 加密密钥
    private static String tokenSignKey = "shop_star_001";

    // 根据id⽣成token
    public static String createToken(Long id) {
        String token = JWT.create()
                .setPayload("id", id)
                .setExpiresAt(new Date(System.currentTimeMillis() +
                        tokenExpiration))
                .setKey(tokenSignKey.getBytes()).sign();
        return token;
    }

    // 从token字符串获取id
    public static Long getId(String token) {
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        Long id = (Long) JWT.of(token).getPayload("id");
        return id;
    }
}