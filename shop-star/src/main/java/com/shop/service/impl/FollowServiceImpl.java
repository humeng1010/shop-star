package com.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.dto.UserDTO;
import com.shop.entity.Follow;
import com.shop.mapper.FollowMapper;
import com.shop.service.IFollowService;
import com.shop.service.IUserService;
import com.shop.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();

        String key = "follows:" + userId;
        if (isFollow) {
            //    关注
            Follow follow = new Follow();
            follow.setUserId(userId).setFollowUserId(id);
            boolean save = this.save(follow);
            if (save) {
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            //    取关
            boolean remove = this.update()
                    .eq("user_id", userId)
                    .eq("follow_user_id", id)
                    .remove();
            if (remove) {
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        Integer count = this.lambdaQuery().eq(Follow::getUserId, userId).eq(Follow::getFollowUserId, id).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 当前用户
        String key = "follows:" + userId;
        // 目标用户
        String key2 = "follows:" + id;
        // 从redis中的set集合求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
