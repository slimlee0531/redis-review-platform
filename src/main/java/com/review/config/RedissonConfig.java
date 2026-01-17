package com.review.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.127:6379").setPassword("123456");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }

/*    @Bean
    public RedissonClient redissonClient2(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.127:6379").setPassword("123456");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.100.127:6379").setPassword("123456");
        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }*/

}
