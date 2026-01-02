package com.review.service.impl;

import cn.hutool.json.JSONUtil;
import com.review.dto.Result;
import com.review.entity.ShopType;
import com.review.mapper.ShopTypeMapper;
import com.review.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有店铺信息
     * @return 按 sort 排序的店铺类型列表
     */
    public List<ShopType> queryAll() {
        // 1. 从Redis中查询缓存
        String key = RedisConstants.CACHE_SHOP_TYPE;
        // 从 Redis 中获取所有元素
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
        // 2. 如果 Redis 中有数据，转换为 List
        if (typedTuples != null && !typedTuples.isEmpty()) {
            List<ShopType> typeList = typedTuples.stream()
                    .map(tuple -> JSONUtil.toBean(tuple.getValue(), ShopType.class))
                    .collect(Collectors.toList());
            return typeList;
        }

        // 3. 如果不存在，mysql 查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 查询到的数据写回 Redis
        if (typeList != null && !typeList.isEmpty()) {
            typeList.forEach(type -> {
                stringRedisTemplate.opsForZSet().add(key, JSONUtil.toJsonStr(type), type.getSort());
            });
        }
        // 5. 返回 List
        return typeList;
    }

}
