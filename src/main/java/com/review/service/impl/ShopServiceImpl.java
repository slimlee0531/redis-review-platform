package com.review.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.review.dto.Result;
import com.review.entity.Shop;
import com.review.mapper.ShopMapper;
import com.review.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.CacheClient;
import com.review.utils.RedisConstants;
import com.review.utils.RedisData;
import com.review.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.review.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据商铺 id 查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 防止缓存穿透 获取店铺信息
        Shop shop = cacheClient
                .queryWithPenetrateGuard(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);

        // 防止缓存击穿 获取店铺信息 互斥锁方式
//        Shop shop = cacheClient
//                .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 防止缓存击穿 获取店铺信息 逻辑过期方式
//        Shop shop = cacheClient
//                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！！！");
        }

        // 返回
        return Result.ok(shop);
    }

    /**
     * 缓存穿透保护 获得店铺信息
     * @param id
     * @return 店铺信息
     */
    public Shop queryWithPenetrationGuard(Long id) {
        // 1. 从 Redis 中查询商铺缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值   "" ！= null
        if (shopJson != null) {     // 说明是 ""
            // 返回 null
            return null;
        }
        // 4. 不存在，根据 id 查询数据库
        Shop shop = getById(id);
        // 5. 不存在，返回错误
        if (shop == null) {
            // 将空值写入 Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回 null
            return null;
        }
        // 6. 存在，写入 Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }

    /**
     * 缓存击穿保护 获取店铺信息
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        // 1. 从 Redis 中查询缓存数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 如果存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 3. 判断是否命中的值是空值
        if (shopJson != null) { // 也就是 ""
            return null;
        }
        // 4. 缓存重构
        // 4.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            Boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 4.3 失败，则休眠重试
                Thread.sleep(50);
                // 递归继续查询
                return queryWithMutex(id);
            }
            // 4.4 成功，根据id查询数据库
            // double check 缓存
            String s = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(s)) {
                shop = JSONUtil.toBean(s, Shop.class);
                return shop;
            }
            if (s != null) {
                return null;
            }
            shop = getById(id);
            // 模拟重建数据缓存的延时
            Thread.sleep(200);
            // 5.不存在，返回错误
            if (shop == null) {
                return null;
            }
            // 6.写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            delLock(lockKey);
        }

        return shop;
    }

    /**
     * 逻辑过期方法处理缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
//            saveShop2RedisCache(id, 20L);
            return null;
        }

        // 2. 若命中缓存，反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean(redisData.getData().toString(), Shop.class);    // 店铺信息
        LocalDateTime expireTime = redisData.getExpireTime();                       // 逻辑过期时间

        // 3. 若没逻辑过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 4. 过期则重建，最终都返回旧的数据
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Boolean isLock = false;
        try {
            isLock = tryLock(lockKey);
            // 若拿到锁，开启重建
            if (isLock) {
                // double check 缓存 若已经被重建好，返回新数据
                String checkShopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(checkShopJson)) {
                    RedisData checkRedisData = JSONUtil.toBean(checkShopJson, RedisData.class);
                    Shop checkShop = JSONUtil.toBean(checkRedisData.getData().toString(), Shop.class);
                    LocalDateTime checkExpireTime = checkRedisData.getExpireTime();
                    if (checkExpireTime.isAfter(LocalDateTime.now())) {
                        return checkShop;
                    }
                }

                // 重建
                CACHE_REBUILD_EXECUTOR.execute(() -> {
                    try {
                        this.saveShop2RedisCache(id, 20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        // 释放互斥锁
                        delLock(lockKey);
                    }
                });
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("获取锁、检查缓存异常", e);
        }

        return shop;
    }

    /**
     * 线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    public Boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    public void delLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 保存店铺信息至 Redis
     * @param shopId
     * @param expireSeconds
     */
    public void saveShop2RedisCache(Long shopId, Long expireSeconds) {
        // 1. 从 MySQL 中查询数据
        Shop shop = getById(shopId);
        if (shop == null) {
            throw new RuntimeException("数据库中无此店铺信息:" + shopId);
        }
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入 Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shopId, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新商铺
     * @param shop
     * @return
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，直接数据库查
            Page<Shop> shopPage = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回
            return Result.ok(shopPage);
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3. 查询 redis，按照距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo()
                // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        // 4. 解析出 id
        if (geoResults == null) {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> contentList = geoResults.getContent(); // 包含店铺id 距离
        if (contentList.size() <= from) {
            // 没有下一页了
            return Result.ok();
        }
        // 4.1. 截取 from - end
        List<Long> idList = new ArrayList<>(contentList.size());
        Map<String, Distance> distanceMap = new HashMap<>(contentList.size()); // 映射：店铺id 用户到店铺的distance
        contentList.stream().skip(from).forEach(geoResult -> {
            // 4.2. 获取店铺 id
            String shopIdStr = geoResult.getContent().getName();
            idList.add(Long.parseLong(shopIdStr));
            // 4.3. 获取距离
            Distance distance = geoResult.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5. 根据 id 查询 Shop
        String idStr = StrUtil.join(",", idList);
        List<Shop> shopList = query().in("id", idList).last("ORDER BY FIELD(id," + idStr + ")").list();
        shopList.forEach(shop -> {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        });

        return Result.ok(shopList);
    }
}
