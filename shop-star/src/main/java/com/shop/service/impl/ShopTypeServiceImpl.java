package com.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.ShopType;
import com.shop.mapper.ShopTypeMapper;
import com.shop.service.IShopTypeService;
import com.shop.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        ValueOperations<String, String> stringStringValueOperations = stringRedisTemplate.opsForValue();
        String cacheShopTypeKey = RedisConstants.CACHE_SHOP_TYPE_KEY;
        String shopTypeJson = stringStringValueOperations.get(cacheShopTypeKey);
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypes);
        }

        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();
        if (typeList.isEmpty()) {
            return Result.fail("店铺类型列表为空");
        }
        String typeListJson = JSONUtil.toJsonStr(typeList);
        stringStringValueOperations
                .set(cacheShopTypeKey, typeListJson);

        return Result.ok(typeList);
    }
}
