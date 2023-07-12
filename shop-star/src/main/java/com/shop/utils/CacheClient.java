package com.shop.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.shop.utils.RedisConstants.CACHE_NULL_TTL;
import static com.shop.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * 操作redis的工具类
 * 封装了设置缓存,设置带有逻辑过期字段的缓存
 * <p>
 * 查询数据:缓存空值解决缓存穿透的查询,互斥锁和逻辑过期解决缓存击穿的查询
 *
 * @author humeng
 */
@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询数据解决缓存穿透
     *
     * @param keyPrefix  key 前缀
     * @param id         查询数据的id
     * @param type       查询数据的类型
     * @param dbFullback 查询数据库的回调函数
     * @param time       缓存过期时间
     * @param unit       过期时间单位
     * @param <R>        数据类型
     * @param <ID>       id的类型
     * @return 返回查询的数据
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFullback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(json)) {
            //    存在,直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断是否是空字符串,防止缓存穿透
        if (json != null) {
            return null;
        }
        // 缓存不存在,去数据库查
        // 使用函数式编程,调用调用者编写的代码
        R r = dbFullback.apply(id);

        if (r == null) {
            //    数据库中也不存在
            //    缓存空值
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 缓存数据
        set(key, r, time, unit);

        return r;
    }

    /**
     * 创建10个固定的线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 逻辑过期解决缓存击穿
     *
     * @param keyPrefix     key前缀
     * @param id            查询的id
     * @param type          返回值的类型
     * @param lockKeyPrefix 锁前缀
     * @param dbFullback    根据id查询具体逻辑
     * @param time          缓存过期时间
     * @param unit          过期时间单位
     * @param <R>           数据类型
     * @param <ID>          id类型
     * @return 返回查询的数据
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFullback, Long time, TimeUnit unit) {

        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);


        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            //    未过期
            return r;
        }
        // 已过期
        // 实现缓存重建，解决缓存击穿问题
        String lockKey = lockKeyPrefix + id;

        boolean lock = tryLock(lockKey);
        if (!lock) {
            // 获取锁失败
            return r;
        }
        // 获取锁成功
        //    DoubleCheck
        // DoubleCheck 若缓存没过期，直接返回。(当某个线程来获取锁后，缓存有可能已经重建完毕)
        json = stringRedisTemplate.opsForValue().get(key);
        redisData = JSONUtil.toBean(json, RedisData.class);
        r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 已经被重建,直接返回
            return r;
        }
        // 开启新线程执行缓存重建
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 执行回调查询数据库
                R dbR = dbFullback.apply(id);
                setWithLogicalExpire(key, dbR, time, unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unLock(lockKey);
            }
        });

        return r;
    }


    /**
     * 互斥锁防止缓存击穿
     *
     * @param keyPrefix     要查询的key前缀
     * @param id            要查询的数据的ID
     * @param type          返回的数据的类型
     * @param lockKeyPrefix 锁的前缀
     * @param dbFullback    根据id查询的逻辑
     * @param time          缓存的时间
     * @param unit          缓存时间的单位
     * @param <R>           数据类型
     * @param <ID>          id的类型
     * @return 返回查询的数据
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, String lockKeyPrefix, Function<ID, R> dbFullback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 如果缓存中的数据不为空,则直接返回数据
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            //    json是空字符串,缓存穿透的时候存储的
            return null;
        }

        String lockKey = lockKeyPrefix + id;
        R r;
        try {
            boolean lock = tryLock(lockKey);
            while (!lock) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                lock = tryLock(lockKey);
            }
            // DoubleCheck
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                //    json是空字符串,缓存穿透的时候存储的
                return null;
            }
            r = dbFullback.apply(id);
            if (r == null) {
                // 缓存空值防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            set(key, r, time, unit);
        } finally {
            unLock(lockKey);
        }

        return r;
    }


    // 获取锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 不直接返回Boolean类型，避免⾃动拆箱时出现空指针异常。
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
