package com.shop.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.User;
import com.shop.mapper.UserMapper;
import com.shop.service.IUserService;
import com.shop.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.shop.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 签到功能：
     *
     * @return
     */
    @Override
    public Result sign() {
        // 获取⽤户id和当前⽇期（年⽉），做为key
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本⽉的第⼏天
        int dayOfMonth = now.getDayOfMonth();
        // 写⼊bitmap，bitmap下标从0开始，因此需要天数-1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计连续登陆
     *
     * @return
     */
    @Override
    public Result signCount() {
        // 获取⽤户id和当前⽇期（年⽉），做为key
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本⽉的第⼏天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本⽉的登陆记录，返回的是⼀个⼗进制数字
        // 等价于 bitfield key get udayOfMonth 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        // 没有任何签到
        if (result == null || result.isEmpty()) {
            return Result.ok();
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok();
        }
        // 循环变量，让这个数字与1进⾏与运算，得到最后⼀个bit位。最后进⾏判断
        int count = 0; // 记录连续签到的天数
        // 1111 1111
        // 0000 0001
        while (true) {
            if ((num & 1) == 0) {
                // 未签到
                break;
            } else {
                // 已签到，计数器+1
                count++;
            }
            // 数字右移，继续下⼀个bit位
            // num >>>= 1;
            num = num >>> 1;
        }
        return Result.ok(count);
    }

}
