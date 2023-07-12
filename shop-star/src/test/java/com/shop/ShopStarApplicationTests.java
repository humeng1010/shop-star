package com.shop;

import com.shop.service.impl.ShopServiceImpl;
import com.shop.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class ShopStarApplicationTests {

    @Resource
    private ShopServiceImpl service;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void testCacheBuild() {
        service.saveShop2Redis(1L, 10L);
    }

    private static final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testRedisIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.createId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        for (int i = 0; i < 300; i++) {
            es.submit(runnable);
        }
        countDownLatch.await();
        stopWatch.stop();
        System.out.println("stopWatch.getTotalTimeMillis() = " + stopWatch.getTotalTimeMillis());

    }


}
