package com.review.service;

import com.review.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IShopTypeService extends IService<ShopType> {

    List<ShopType> queryAll();

}
