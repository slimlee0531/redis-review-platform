package com.review.service.impl;

import com.review.dto.Result;
import com.review.entity.SeckillVoucher;
import com.review.entity.VoucherOrder;
import com.review.mapper.VoucherOrderMapper;
import com.review.service.ISeckillVoucherService;
import com.review.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.RedisIdWorker;
import com.review.utils.SimpleRedisLock;
import com.review.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 优惠券秒杀
     * @param voucherId
     * @return
     */
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

        Long userId = UserHolder.getUser().getId();
        /*
        synchronized (userId.toString().intern()) { // 保证锁在方法中事务执行后(插入订单数据)才释放
            // 这里的调用本质是：this.createVoucherOrder(voucherId)  代理对象没机会介入
//            return createVoucherOrder(voucherId);

            // 获取代理对象 (事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
         */

        // 获取 Redis 锁
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
        boolean locked = lock.tryLock(5);
        // 如果获取失败，返回
        if (!locked) {
            return Result.fail("不可重复下单！");
        }

        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * 创建优惠券订单
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) { // 若锁方法 非静态方法的 synchronized，锁的是当前 Service 对象本身（this） 锁的粒度太大
        // 5. 一人一单逻辑                               // synchronized和 @Transactional结合使用会导致「锁释放早于事务提交」
        // 5.1. 用户id
        Long userId = UserHolder.getUser().getId();

        // 但是如果在方法内上锁，若锁在事务提交之前释放，其他线程也会进来
//        synchronized (userId.toString().intern()) {     // 只锁当前用户
            int count = query()
                    .eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2. 判断是否存在
            if (count > 0) {
                return Result.fail("用户已经购买过了！");
            }

            // 6. 扣减库存
            boolean updated = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
//                .eq("stock", seckillVoucher.getStock()) // CAS 乐观锁
                    .gt("stock", 0)     // 乐观锁成功率太低  where stock > 0
                    .update();
            if (!updated) {
                return Result.fail("该优惠券已经被抢光了！");
            }

            // 7. 创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1. 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2. 用户id
            voucherOrder.setUserId(userId);
            // 7.3. 代金券id
            voucherOrder.setVoucherId(voucherId);

            // 8. 保存订单至 VoucherOrder 表
            save(voucherOrder);

            return Result.ok(orderId);
//        }
    }

}
