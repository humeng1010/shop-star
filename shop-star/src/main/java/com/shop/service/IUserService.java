package com.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.shop.dto.Result;
import com.shop.entity.User;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sign();

    Result signCount();
}
