package com.review.service;

import com.review.dto.Result;
import com.review.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /**
     * 使用代理对象开启事务注解
     * @param voucherId
     * @return
     */
    Result createVoucherOrder(Long voucherId);

}
