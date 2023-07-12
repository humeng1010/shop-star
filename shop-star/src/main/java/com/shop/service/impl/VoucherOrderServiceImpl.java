package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.SeckillVoucher;
import com.shop.entity.VoucherOrder;
import com.shop.mapper.VoucherOrderMapper;
import com.shop.service.ISeckillVoucherService;
import com.shop.service.IVoucherOrderService;
import com.shop.utils.RedisIdWorker;
import com.shop.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // @Resource
    // private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Override
    // @Transactional 如果这个地方还有事务,会导致事务套事务的情况,这个事务还没有提交,里面的锁就释放掉了,导致还是一人多单!
    public Result seckillVoucher(Long voucherId) {
        if (voucherId != null && voucherId <= 0) {
            return Result.fail("优惠券不存在");
        }
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }

        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        // 加锁,进行一人一单的秒杀
        Long userId = UserHolder.getUser().getId();
        // synchronized (userId.toString().intern()) {
        // 加分布式锁,解决分布式系统不同的JVM出现不同的锁对象
        // ILock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        // 获取默认的锁,不等待,超时时间30秒
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            //    当前用户没有获取到锁,说明redis中已经有当前用户的key了,已经参与了秒杀
            return Result.fail("只允许一人一单");
        }
        try {
            // 使用AopContext获取当前对象的代理对象,避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
            // return voucherOrderService.createVoucherOrder(voucherId);
            // }
        } finally {
            // 释放锁
            // lock.unlock();
            rLock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //⼀⼈⼀单
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(queryWrapper);
        if (count > 0) {
            return Result.fail("⽤户已经购买过⼀次");
        }

        // 扣减库存
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                // CAS 乐观锁解决超卖
                .gt("stock", 0)
                .update();
        if (!update) {
            return Result.fail("库存不足");
        }

        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.createId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
        // }
    }
}
