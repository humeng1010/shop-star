package com.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.Shop;
import com.shop.mapper.ShopMapper;
import com.shop.service.IShopService;
import com.shop.utils.CacheClient;
import com.shop.utils.RedisConstants;
import com.shop.utils.RedisData;
import com.shop.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 不需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
            LambdaQueryWrapper<Shop> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Shop::getTypeId, typeId);
            this.page(page, queryWrapper);
            return Result.ok(page.getRecords());
        }

        // 计算分⻚参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 查询redis，按照距离排序，分⻚，查询半径为5公⾥以内的商户。 结果为shopId - distance
        String key = SHOP_GEO_KEY + typeId;
        // 等价于 GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE LIMIT end ：查询0-end条信息
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // 截取出实际需要的分⻚数据 from - end 的部分 并解析出shopId - distance
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下⼀⻚
            return Result.ok(Collections.emptyList());
        }
        List<Long> idList = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        list.stream().skip(from).forEach(result -> {
            // 获取店铺id
            String shopId = result.getContent().getName();
            idList.add(Long.valueOf(shopId));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        // 根据id查询店铺（注意有序）
        String idListStr = StrUtil.join(",", idList);
        LambdaQueryWrapper<Shop> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Shop::getId, idList).last("ORDER BY FIELD(id," + idListStr + ")");
        List<Shop> shopList = this.list(queryWrapper);
        // 店铺与距离相映射
        shopList.forEach(shop -> {
            Distance distance = distanceMap.get(shop.getId().toString());
            shop.setDistance(distance.getValue());
        });
        return Result.ok(shopList);
    }

    /**
     * 定时任务： 把店铺的地理位置写⼊Redis，每⼀分钟执⾏⼀次
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void loadShopData() {
        // 查询店铺信息，然后按照typeID进⾏分组，放到⼀个集合中
        List<Shop> list = this.list();
        Map<Long, List<Shop>> map =
                list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 分批写⼊Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 类型Id
            Long typeId = entry.getKey();
            // 相同类型的店铺集合
            List<Shop> value = entry.getValue();
            String key = SHOP_GEO_KEY + typeId;
            //⽅式⼀：⼀个⼀个点写⼊redis GEOADD key 经度 纬度 member
            // value.forEach(shop->{
            // stringRedisTemplate.opsForGeo().add(key,newPoint(shop.getX(),shop.getY()),shop.getId().toString());
            //});
            //⽅式⼆： 计算出每个店铺的经纬度后的出⼀个GeoLocation集合，然后统⼀写⼊redis
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = value.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key, geoLocationList);
        }
    }
}
