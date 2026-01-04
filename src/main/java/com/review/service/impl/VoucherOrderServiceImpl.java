package com.review.service.impl;

import com.review.dto.Result;
import com.review.entity.SeckillVoucher;
import com.review.entity.VoucherOrder;
import com.review.mapper.VoucherOrderMapper;
import com.review.service.ISeckillVoucherService;
import com.review.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.RedisIdWorker;
import com.review.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 优惠券秒杀
     * @param voucherId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3. 判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("该秒杀优惠券已经结束！");
        }
        // 4. 判断库存
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("该优惠券已经被抢光了！");
        }
        // 5. 扣减库存
        boolean updated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
//                .eq("stock", seckillVoucher.getStock()) // CAS 乐观锁
                .gt("stock", 0)     // 乐观锁成功率太低  where stock > 0
                .update();
        if (!updated) {
            return Result.fail("该优惠券已经被抢光了！");
        }

        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1. 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2. 用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3. 代金券id
        voucherOrder.setVoucherId(voucherId);

        // 7. 保存订单至 VoucherOrder 表
        save(voucherOrder);

        return Result.ok(orderId);
    }

}
