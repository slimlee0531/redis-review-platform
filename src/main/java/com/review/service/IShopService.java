package com.review.service;

import com.review.dto.Result;
import com.review.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    /**
     * 根据商铺 id 查询商铺信息
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新商铺
     * @param shop
     * @return
     */
    Result update(Shop shop);

}
