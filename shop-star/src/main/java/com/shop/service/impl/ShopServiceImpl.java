package com.shop.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.Shop;
import com.shop.mapper.ShopMapper;
import com.shop.service.IShopService;
import com.shop.utils.CacheClient;
import com.shop.utils.RedisConstants;
import com.shop.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.shop.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 使用缓存空值解决缓存穿透(某个数据redis缓存中不存在,数据库中也不存在,访问该数据会一直请求数据库)
     * 使用N+n解决缓存雪崩(同一时间大量的key同时过期)
     * 使用互斥锁解决缓存击穿(热点key突然失效了,导致大量请求到达数据库)
     *
     * @param id shopId
     * @return shop info
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = cacheClient
        //         .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class,
        //              this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
        Shop shop = cacheClient
                .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY,
                        this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY,
        //                 this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        if (shop == null) {
            return Result.fail("商铺不存在");
        }

        return Result.ok(shop);
    }


    /**
     * 缓存重建
     * 保存shop信息到redis,包含逻辑过期字段
     *
     * @param id            shop id
     * @param expireSeconds ttl
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {
        if (shop == null) {
            return Result.fail("请求参数不能为空");
        }
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        this.updateById(shop);
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
