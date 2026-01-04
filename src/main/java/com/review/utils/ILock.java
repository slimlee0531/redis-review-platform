package com.review.utils;

public interface ILock {

    /**
     * 获取锁
     * @param timeoutSec 锁持有的时间
     * @return  true 代表成功
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();

}
