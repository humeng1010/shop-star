package com.shop.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 开始时间戳
    public static final long BEGIN_TIMESTAMP = 1672531200L;
    // 序列号的位数
    private static final int COUNT_BITS = 32;

    public long createId(String keyPrefix) {
        // 1.⽣成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2.⽣成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 根据key进⾏⾃增 （increment：没有key则创建⼀个并初始值为0，因此不需要考虑NPE）
        long count = stringRedisTemplate.opsForValue().increment("icr:" +
                keyPrefix + ":" + date);
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    //⽣成开始时间戳
    private void createTimestamp() {
        //⽣成2023年1⽉1⽇0点0分0秒对应的秒数
        LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second); // 1672531200
    }
}
