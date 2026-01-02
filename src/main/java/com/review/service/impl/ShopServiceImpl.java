package com.review.service.impl;

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

import static com.review.utils.RedisConstants.CACHE_NULL_TTL;
import static com.review.utils.RedisConstants.CACHE_SHOP_TTL;

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
        // 防止缓存穿透获取店铺信息
        Shop shop = queryWithPenetrationGuard(id);

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
