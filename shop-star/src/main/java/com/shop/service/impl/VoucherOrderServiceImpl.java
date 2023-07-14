package com.shop.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shop.dto.Result;
import com.shop.entity.VoucherOrder;
import com.shop.mapper.VoucherOrderMapper;
import com.shop.service.ISeckillVoucherService;
import com.shop.service.IVoucherOrderService;
import com.shop.utils.RedisIdWorker;
import com.shop.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
@Slf4j
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 依赖注入后执行
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    /**
     * 获取消息队列中的信息
     */
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(queueName))) {
                log.info("redis中没有该stream队列,准备创建");
                stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
                log.info("创建stream消息队列成功");
            }
            while (true) {
                try {
                    log.info("c1消费者准备获取从g1组中获取消息");
                    //    获取stream消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.order >

                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate
                            .opsForStream()
                            .read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                                    StreamOffset.create(queueName, ReadOffset.lastConsumed())
                            );

                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败说明没有消息,继续下一次获取
                        log.info("c1消费者从g1组中 没有获取到 消息");
                        continue;
                    }
                    log.info("c1消费者从g1组中 获取到 消息!!! 准备解析该消息");
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> message = list.get(0);
                    Map<Object, Object> orderMap = message.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(orderMap, voucherOrder, true);
                    log.info("解析消息成功,消息内容:{}", voucherOrder);
                    // 如果获取成功则 创建订单
                    handlerVoucherOrder(voucherOrder);
                    //    ACK 确认 SACK stream.orders g1 id
                    log.info("处理成功准备发送ack确认标识");
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", message.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //    取出没有确认的消息,再次处理
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            while (true) {
                try {
                    //    获取stream消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.order 0
                    log.warn("c1消费者从g1组中 获取 未经确认的消息");
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate
                            .opsForStream()
                            .read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(queueName, ReadOffset.from("0"))
                            );

                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败说明pendingList中没有没有被消费确认的消息,结束循环
                        log.warn("c1消费者从g1组中 获取 未经确认的消息 为空 所有消息都被确认");
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> message = list.get(0);
                    Map<Object, Object> orderMap = message.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(orderMap, voucherOrder, true);
                    log.warn("c1消费者从g1组中 获取 未经确认的消息 解析后为{}", voucherOrder);
                    // 如果获取成功则 创建订单
                    handlerVoucherOrder(voucherOrder);
                    log.info("处理成功准备发送ack确认标识");
                    //    ACK 确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", message.getId());

                } catch (Exception e) {
                    log.error("处理pending-list订单异常,准备再次获取pending-list消息", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }

        }
    }

    /*private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //    获取队列中的订单信息
                    // take:检索并删除此队列的头部，如有必要，请等待元素可用
                    VoucherOrder order = orderTasks.take();
                    //    创建订单
                    handlerVoucherOrder(order);

                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handlerVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        // 获取默认的锁,不等待,超时时间30秒
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 使用AopContext获取当前对象的代理对象,避免事务失效
            proxy.createVoucherOrder(order);
            // return voucherOrderService.createVoucherOrder(voucherId);
            // }
        } finally {
            // 释放锁
            // lock.unlock();
            rLock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    // @Transactional 如果这个地方还有事务,会导致事务套事务的情况,这个事务还没有提交,里面的锁就释放掉了,导致还是一人多单!
    public Result seckillVoucher(Long voucherId) {
        //    执行Lua脚本
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.createId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        assert result != null;
        int r = result.intValue();
        //    判断结果是否是0
        if (r != 0) {
            // 减掉生成id造成的缓存中的销量增加
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
            stringRedisTemplate.opsForValue().decrement("icr:" +
                    "order" + ":" + date);
            //    不为0 没有购买资格
            String message = null;
            if (r == 1) {
                message = "库存不足";
            }
            if (r == 2) {
                message = "不能重复下单";
            }
            if (r == 3) {
                message = "优惠券已失效";
            }
            return Result.fail(message);
        }

        // 获取当前类的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //    返回订单id
        return Result.ok(orderId);
    }

    // 阻塞队列实现异步秒杀
    /*@Override
    // @Transactional 如果这个地方还有事务,会导致事务套事务的情况,这个事务还没有提交,里面的锁就释放掉了,导致还是一人多单!
    public Result seckillVoucher(Long voucherId) {
        //    执行Lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        assert result != null;
        int r = result.intValue();
        //    判断结果是否是0
        if (r != 0) {
            //    不为0 没有购买资格
            String message = null;
            if (r == 1) {
                message = "库存不足";
            }
            if (r == 2) {
                message = "不能重复下单";
            }
            if (r == 3) {
                message = "优惠券已失效";
            }
            return Result.fail(message);
        }

        //    为0 有购买资格
        long orderId = redisIdWorker.createId("order");
        //    保存到阻塞队列 等待异步执行
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        // 获取当前类的代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //    返回订单id
        return Result.ok(orderId);
    }*/

    /*@Override
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
    }*/

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        //⼀⼈⼀单
        Long userId = order.getUserId();
        Long voucherId = order.getVoucherId();
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId);
        int count = this.count(queryWrapper);
        if (count > 0) {
            log.error("⽤户已经购买过⼀次");
            return;
        }

        // 扣减库存
        boolean update = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                // CAS 乐观锁解决超卖
                .gt("stock", 0)
                .update();
        if (!update) {
            log.error("库存不足");
            return;
        }

        save(order);
    }

    /*@Transactional
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
    }*/
}
