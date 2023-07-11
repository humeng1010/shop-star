package com.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shop.dto.Result;
import com.shop.entity.ShopType;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();

}
