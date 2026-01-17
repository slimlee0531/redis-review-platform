package com.review.service.impl;

import com.review.dto.Result;
import com.review.entity.VoucherOrder;
import com.review.mapper.VoucherOrderMapper;
import com.review.service.ISeckillVoucherService;
import com.review.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.review.utils.RedisIdWorker;
import com.review.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;

    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 用于线程池处理的任务
    // 当初始化完毕后，就会去队列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取队列中的订单信息 阻塞队列
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock redissonClientLock = redissonClient.getLock("lock:order:" + userId);
        // 3. 尝试获取锁
        boolean isLock = redissonClientLock.tryLock();
        // 4. 判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单！！");
            return;
        }
        try {
            // 由于 Spring 事务放在 ThreadLocal 中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            redissonClientLock.unlock();
        }
    }

    /**
     * 优惠券秒杀
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行 lua 脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2. 判断是否为 0 （有购买资格）
        if (r != 0) {
            // 2.1. 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.2. 订单信息
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        // 2.3. 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4. 返回订单 id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        // 判断是否存在
        if (count > 0) {
            log.error("用户已经购买过了");
            return;
        }

        // 扣减库存
        boolean updated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!updated) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

    /**
     * 优惠券秒杀
     * @param voucherId
     * @return
     */
/*    public Result seckillVoucher(Long voucherId) {
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

        synchronized (userId.toString().intern()) { // 保证锁在方法中事务执行后(插入订单数据)才释放
            // 这里的调用本质是：this.createVoucherOrder(voucherId)  代理对象没机会介入
//            return createVoucherOrder(voucherId);

            // 获取代理对象 (事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }


        // 获取 Redis 锁
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = lock.tryLock();
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
    }*/

    /**
     * 创建优惠券订单
     */
    /*
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
    }*/

}
