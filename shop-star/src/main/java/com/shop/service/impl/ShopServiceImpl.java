package com.shop.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.Shop;
import com.shop.mapper.ShopMapper;
import com.shop.service.IShopService;
import com.shop.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.shop.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.shop.utils.RedisConstants.LOCK_SHOP_TTL;

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

    @Override
    public Result queryById(Long id) {
        if (id != null && id <= 0) {
            return Result.fail("参数有误");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        ValueOperations<String, String> stringStringValueOperations = stringRedisTemplate.opsForValue();
        String shopJson = stringStringValueOperations.get(key);

        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        if (shopJson != null) {
            //    说明这个值是""空字符串,防止缓存穿透去查询数据库,在这里直接返回信息不存在
            return Result.fail("店铺不存在");
        }
        // 实现缓存重建，解决缓存击穿问题
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            boolean lock = tryLock(lockKey);
            while (!lock) {
                // 获取锁失败则休眠⼀下，然后重新获取
                TimeUnit.MILLISECONDS.sleep(50);
                lock = tryLock(lockKey);
            }
            // DoubleCheck(此时有可能别的线程已经重新构建好缓存)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 这⾥判断的时shopJson是否真的有值，不包括空值
            if (StringUtils.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop);
            }

            // 模拟重建的延迟
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            shop = this.getById(id);
            if (shop == null) {
                // 缓存空值,解决缓存穿透
                stringStringValueOperations.set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            stringStringValueOperations
                    .set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }

        return Result.ok(shop);
    }

    // 获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 不直接返回Boolean类型，避免⾃动拆箱时出现空指针异常。
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
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
