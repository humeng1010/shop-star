package com.shop.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shop.dto.LoginFormDTO;
import com.shop.dto.Result;
import com.shop.dto.UserDTO;
import com.shop.entity.User;
import com.shop.entity.UserInfo;
import com.shop.mapper.UserMapper;
import com.shop.service.IUserInfoService;
import com.shop.service.IUserService;
import com.shop.utils.JwtHelper;
import com.shop.utils.RegexUtils;
import com.shop.utils.SystemConstants;
import com.shop.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.shop.utils.RedisConstants.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        // 1.校验⼿机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.若不符合，返回错误信息
            return Result.fail("⼿机号格式错误");
        }
        // 3.若符合，⽣成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis key-⼿机号 value-验证码 并设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL,
                TimeUnit.MINUTES);
        // 5.发送验证码 (要调⽤第三⽅，这⾥不做)
        log.info("发送短信验证码：{}", code);
        return Result.ok();
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 校验⼿机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("⼿机号格式错误");
        }
        // 从redis中校验验证码
        String cacheCode =
                stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (!code.equals(cacheCode)) {
            return Result.fail("验证码错误");
        }
        // 查数据库
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StrUtil.isNotBlank(phone), "phone", phone);
        User user = userService.getOne(queryWrapper);
        // 判断⽤户是否存在，不存在则创建⼀个
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //⽣成token
        String token = JwtHelper.createToken(user.getId());
        // 将user转成map后进⾏hash存储,设置有效期
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // user转map时，由于id是Long类型，⽽StringRedisTemplate只⽀持String类型，因此需要⾃定义映射规则
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, map);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + IdUtil.nanoId(10));
        return user;
    }

    /**
     * 登出功能
     *
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader("authorization") String authorization) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("您还未登录");
        }

        String tokenKey = LOGIN_USER_KEY + authorization;
        Boolean isSuccess = stringRedisTemplate.delete(tokenKey);
        if (Boolean.FALSE.equals(isSuccess)) {
            return Result.fail("退出失败");
        }

        return Result.ok("退出成功");
    }

    @GetMapping("/me")
    public Result me() {
        UserDTO userDTO = UserHolder.getUser();
        return Result.ok(userDTO);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
