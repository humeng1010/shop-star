package com.shop.utils;

public interface ILock {
    /**
     * 获取锁
     *
     * @param timeoutSet 超时时间
     * @return
     */
    boolean tryLock(long timeoutSet);

    /**
     * 释放锁
     */
    void unlock();
}

