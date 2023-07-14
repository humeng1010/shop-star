package com.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shop.dto.Result;
import com.shop.entity.Shop;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
