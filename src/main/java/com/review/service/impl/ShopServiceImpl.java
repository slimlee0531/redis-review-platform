package com.review.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.review.dto.Result;
import com.review.entity.Shop;
import com.review.mapper.ShopMapper;
import com.review.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.review.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据商铺 id 查询商铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        // 防止缓存穿透 获取店铺信息
//        Shop shop = queryWithPenetrationGuard(id);

        // 防止缓存击穿 获取店铺信息 互斥锁方式
        Shop shop = queryWithMutex(id);

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

}
