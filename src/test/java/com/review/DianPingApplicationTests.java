package com.review;

import cn.hutool.core.date.DateTime;
import com.review.entity.Shop;
import com.review.service.impl.ShopServiceImpl;
import com.review.utils.CacheClient;
import com.review.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.review.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.review.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class DianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void testSaveShop() {
//        shopService.saveShop2RedisCache(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    private final ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("Time = " + (end - begin));
    }

    @Resource
    private RedissonClient redissonClient;

    @Test
    void testRedisson() throws InterruptedException {
        // 获取锁（可重入），指定锁的名称
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);

        if (isLock) {
            try {
                System.out.println("执行业务");
            } finally {
                lock.unlock();
            }
        }
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void loadShopData() {
        // 1. 查询店铺信息
        List<Shop> shopList = shopService.list();
        // 2. 店铺按照类型分组 typeId List
        Map<Long, List<Shop>> longListMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 分批次按 typeId 写入 Redis
        longListMap.forEach((typeId, shopList1) -> {
            String key = SHOP_GEO_KEY + typeId;
            // 存储 店铺id 位置
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList1.size());
            // 把店铺位置信息加入到 locations
            shopList1.forEach(shop -> {
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString())
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            });
            stringRedisTemplate.opsForGeo().add(key, locations);
        });
    }

}
