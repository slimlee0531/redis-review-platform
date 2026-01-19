# Redis Review Platform

[English Version](./README_EN.md)

## 项目简介

Redis Review Platform是一个基于Spring Boot和Redis构建的综合性点评平台，通过Redis实现了高性能、高并发的业务场景，包括商铺查询、优惠券秒杀、地理位置搜索、用户关注、点赞等功能。项目充分利用Redis的各种特性，解决了实际业务中的性能瓶颈和并发问题。

## 技术栈

- **后端框架**：Spring Boot 
- **ORM框架**：MyBatis Plus
- **缓存数据库**：Redis 6.x
- **分布式锁**：Redisson
- **序列化工具**：Hutool JSON
- **数据库**：MySQL 8.x

## 核心功能与Redis应用

### 1. 商铺缓存系统

#### 缓存穿透保护
- **实现方式**：缓存空值策略
- **Redis应用**：将不存在的商铺ID缓存为空字符串，设置较短的过期时间（2分钟）
- **代码位置**：`ShopServiceImpl.queryWithPenetrateGuard()` 和 `CacheClient.queryWithPenetrateGuard()`

#### 缓存击穿防护
- **实现方式**：
  - 互斥锁：基于Redis的`setnx`实现分布式锁
  - 逻辑过期：设置缓存的逻辑过期时间，过期后异步重建缓存
- **Redis应用**：
  - 互斥锁：使用`setIfAbsent`实现分布式锁
  - 逻辑过期：在Redis中存储包含过期时间的对象
- **代码位置**：
  - 互斥锁：`ShopServiceImpl.queryWithMutex()` 和 `CacheClient.queryWithMutex()`
  - 逻辑过期：`ShopServiceImpl.queryWithLogicalExpire()` 和 `CacheClient.queryWithLogicalExpire()`

#### 缓存更新策略
- **实现方式**：双写一致性（先更新数据库，再删除缓存）
- **Redis应用**：使用`delete`命令删除缓存
- **代码位置**：`ShopServiceImpl.update()`

### 2. 高并发秒杀系统

#### 秒杀核心流程
- **实现方式**：Lua脚本 + Redis Stream
- **Redis应用**：
  - 使用Lua脚本保证库存检查、扣减和订单创建的原子性
  - 使用Redis Stream实现异步订单处理
- **代码位置**：
  - Lua脚本：`src/main/resources/seckill.lua`
  - 秒杀服务：`VoucherOrderServiceImpl.seckillVoucher()`
  - 异步处理：`VoucherOrderServiceImpl.VoucherOrderHandler`

#### 防重复下单
- **实现方式**：基于Redis的Set数据结构
- **Redis应用**：使用`SISMEMBER`命令检查用户是否已下单
- **代码位置**：`seckill.lua`脚本第29行

#### 库存扣减
- **实现方式**：Redis原子操作
- **Redis应用**：使用`incrby`命令原子性扣减库存
- **代码位置**：`seckill.lua`脚本第35行

### 3. 地理位置搜索

#### 附近商铺查询
- **实现方式**：Redis GEO数据结构
- **Redis应用**：
  - 使用`GEOADD`添加商铺地理位置
  - 使用`GEORADIUS`或`GEOSEARCH`查询附近商铺
- **代码位置**：`ShopServiceImpl.queryShopByType()`

### 4. 用户社交功能

#### 关注与取关
- **实现方式**：Redis Set数据结构
- **Redis应用**：使用Set存储用户关注关系
- **代码位置**：`FollowController`

#### 点赞功能
- **实现方式**：Redis Sorted Set数据结构
- **Redis应用**：
  - 使用Sorted Set存储点赞用户和点赞时间，score为点赞时间戳
  - 支持按点赞时间排序显示点赞用户
  - 支持点赞/取消点赞的原子操作
- **核心流程**：
  1. 检查用户是否已点赞（查询Sorted Set中是否存在用户ID）
  2. 若已点赞，取消点赞并减少点赞数
  3. 若未点赞，添加点赞并增加点赞数
  4. 将用户ID从Sorted Set中添加/移除
- **代码位置**：`BlogServiceImpl.likeBlog()`和`BlogServiceImpl.isBlogLiked()`

#### 关注Feed流
- **实现方式**：Redis Sorted Set数据结构
- **Redis应用**：
  - 使用Sorted Set存储用户的关注动态，score为发布时间戳
  - 支持按时间倒序查询
  - 支持滚动分页加载
- **核心流程**：
  1. 用户发布博客时，推送至所有关注者的Feed流
  2. 使用`ZREVRANGEBYSCORE`实现按时间倒序的滚动分页查询
  3. 支持相同时间戳的偏移处理
- **代码位置**：`BlogServiceImpl.saveBlog()`和`BlogServiceImpl.queryBlogOfFollow()`

### 5. 用户签到系统

#### 签到功能
- **实现方式**：Redis BitMap数据结构
- **Redis应用**：使用BitMap存储用户签到记录
- **代码位置**：`UserController.sign()`

#### 连续签到统计
- **实现方式**：Redis BitMap的位运算
- **Redis应用**：使用`BITFIELD`和`BITCOUNT`命令统计连续签到天数
- **代码位置**：`UserController.getSignCount()`

## Redis配置与工具类

### Redisson配置
- **配置文件**：`RedissonConfig.java`
- **功能**：配置Redisson客户端，用于分布式锁和其他高级Redis功能

### Redis工具类

### CacheClient
封装了Redis缓存的各种操作，提供了统一的缓存访问接口：
- 普通缓存设置与获取
- 逻辑过期缓存设置
- 带穿透保护的缓存查询
- 带互斥锁的缓存查询
- 带逻辑过期的缓存查询

### RedisIdWorker
基于Redis实现的全局唯一ID生成器，具有高性能、高可用特性：
- **实现原理**：使用"时间戳 + 序列号"的组合方式
  - 时间戳：距离固定开始时间的秒数
  - 序列号：Redis自增生成，32位长度
- **核心算法**：`(当前时间戳 - 开始时间戳) << 32 | 序列号`
- **应用场景**：订单ID、优惠券ID等全局唯一标识
- **代码位置**：`RedisIdWorker.nextId()`

### SimpleRedisLock
基于Redis实现的分布式锁，支持集群环境：
- **实现原理**：
  - 获取锁：使用`setIfAbsent`命令设置带过期时间的键值对
  - 线程标识：使用"服务器ID + 线程ID"确保锁的唯一性
  - 解锁机制：使用Lua脚本保证解锁的原子性，防止误删其他线程的锁
- **核心特性**：
  - 支持过期时间自动释放锁
  - 防止锁的误删除
  - 支持集群环境
- **应用场景**：缓存重建、秒杀等需要互斥的场景
- **代码位置**：`SimpleRedisLock.tryLock()` 和 `SimpleRedisLock.unlock()`

## 用户认证与Redis结合

项目使用Redis实现了高效的用户认证机制：

- **令牌管理**：
  - 用户登录成功后，生成token并存储到Redis
  - 使用`LOGIN_USER_KEY + token`作为键，用户信息作为值（Hash结构）
  - 设置token的过期时间（36000秒，即10小时）

- **令牌刷新**：
  - `RefreshTokenInterceptor`拦截器自动刷新token有效期
  - 当用户访问需要认证的接口时，自动延长token的过期时间
  - 避免用户在活跃期间token过期

- **用户信息存储**：
  - 使用`ThreadLocal`存储当前登录用户信息，提高访问效率
  - `LoginInterceptor`验证用户是否登录

## Lua脚本应用

项目广泛使用Lua脚本保证Redis操作的原子性：

### 秒杀脚本（seckill.lua）
- **核心功能**：保证库存检查、扣减和订单创建的原子性
- **执行流程**：
  1. 检查库存是否充足
  2. 检查用户是否已下单
  3. 扣减库存
  4. 记录用户下单信息
  5. 发送消息到Redis Stream

### 解锁脚本（unlock.lua）
- **核心功能**：保证分布式锁解锁的原子性
- **执行流程**：
  1. 获取锁中的线程标识
  2. 与当前线程标识比较
  3. 若一致则删除锁，否则直接返回

### Lua脚本的优势
- 减少网络开销：将多个Redis命令合并为一个请求
- 保证原子性：脚本中的所有命令作为一个原子操作执行
- 提高性能：减少了命令执行的网络延迟

## 项目结构

```
src/main/java/com/review/
├── config/              # 配置类
│   ├── MvcConfig.java
│   ├── MybatisConfig.java
│   ├── RedissonConfig.java
│   └── WebExceptionAdvice.java
├── controller/          # 控制器
│   ├── BlogCommentsController.java
│   ├── BlogController.java
│   ├── FollowController.java
│   ├── ShopController.java
│   ├── ShopTypeController.java
│   ├── UploadController.java
│   ├── UserController.java
│   ├── VoucherController.java
│   └── VoucherOrderController.java
├── dto/                 # 数据传输对象
│   ├── LoginFormDTO.java
│   ├── Result.java
│   ├── ScrollResult.java
│   └── UserDTO.java
├── entity/              # 实体类
│   ├── Blog.java
│   ├── BlogComments.java
│   ├── Follow.java
│   ├── SeckillVoucher.java
│   ├── Shop.java
│   ├── ShopType.java
│   ├── User.java
│   ├── UserInfo.java
│   ├── Voucher.java
│   └── VoucherOrder.java
├── mapper/              # Mapper接口
│   ├── BlogCommentsMapper.java
│   ├── BlogMapper.java
│   ├── FollowMapper.java
│   ├── SeckillVoucherMapper.java
│   ├── ShopMapper.java
│   ├── ShopTypeMapper.java
│   ├── UserInfoMapper.java
│   ├── UserMapper.java
│   ├── VoucherMapper.java
│   └── VoucherOrderMapper.java
├── service/             # 服务层
│   ├── impl/           # 服务实现
│   └── *Service.java   # 服务接口
├── utils/              # 工具类
│   ├── CacheClient.java
│   ├── ILock.java
│   ├── LoginInterceptor.java
│   ├── PasswordEncoder.java
│   ├── RedisConstants.java
│   ├── RedisData.java
│   ├── RedisIdWorker.java
│   ├── RefreshTokenInterceptor.java
│   ├── RegexPatterns.java
│   ├── RegexUtils.java
│   ├── SimpleRedisLock.java
│   ├── SystemConstants.java
│   └── UserHolder.java
└── DianPingApplication.java  # 启动类
```

## Redis常量定义

核心Redis键名和过期时间定义在`RedisConstants.java`中：

| 常量名 | 键名格式 | 过期时间 | 用途 |
|-------|---------|---------|------|
| CACHE_SHOP_KEY | cache:shop:{id} | 30分钟 | 商铺缓存 |
| LOCK_SHOP_KEY | lock:shop:{id} | 10分钟 | 商铺缓存重建锁 |
| SECKILL_STOCK_KEY | seckill:stock:{id} | - | 秒杀库存 |
| BLOG_LIKED_KEY | blog:liked:{id} | - | 博客点赞记录 |
| FEED_KEY | feed:{id} | - | 用户关注动态 |
| SHOP_GEO_KEY | shop:geo:{id} | - | 商铺地理位置 |
| USER_SIGN_KEY | sign:{id} | - | 用户签到记录 |

## Redis数据结构使用总结

| 数据结构 | 应用场景 | 核心命令 |
|---------|---------|---------|
| String | 商铺缓存、秒杀库存、分布式锁 | `SET`、`GET`、`INCRBY`、`SETNX` |
| Hash | 用户信息存储 | `HSET`、`HGET`、`HENTRIES` |
| List | - | - |
| Set | 防重复下单、用户关注 | `SISMEMBER`、`SADD` |
| Sorted Set | 博客点赞排序、关注Feed流 | `ZADD`、`ZRANGE`、`ZREVRANGEBYSCORE`、`ZSCORE` |
| Geo | 附近商铺查询 | `GEOADD`、`GEOSEARCH` |
| BitMap | 用户签到 | `SETBIT`、`GETBIT`、`BITCOUNT`、`BITFIELD` |
| Stream | 秒杀订单异步处理 | `XADD`、`XREADGROUP`、`XACK` |

## Redis与Spring Data Redis集成

项目使用Spring Data Redis作为Redis客户端，提供了丰富的Redis操作接口：

- **自动配置**：Spring Boot自动配置Redis连接池和客户端
- **模板类**：
  - `StringRedisTemplate`：针对字符串类型的Redis操作
  - `RedisTemplate`：针对对象类型的Redis操作
- **高级特性支持**：
  - Redis Stream的消息队列功能
  - Geo数据结构的地理位置查询
  - Lua脚本的执行支持
  - Redis连接池管理

## 性能优化亮点

1. **缓存分层策略**：根据数据访问频率和更新频率设置不同的缓存策略
2. **异步缓存重建**：使用线程池异步重建缓存，避免阻塞用户请求
3. **Lua脚本原子操作**：将多个Redis命令封装为Lua脚本，保证操作的原子性
4. **Redis Stream消息队列**：使用Stream实现秒杀订单的异步处理，提高系统吞吐量
5. **地理位置索引**：利用Redis GEO数据结构实现高效的附近商铺查询

## 快速开始

### 环境要求

- JDK 1.8+ 
- MySQL 8.0+ 
- Redis 6.0+ 

### 配置文件

1. 修改`application.yml`中的数据库和Redis配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/redis_review?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  redis:
    host: localhost
    port: 6379
    password: 
    database: 0
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
        max-wait: 1000ms
```

2. 修改`RedissonConfig.java`中的Redis地址配置

```java
config.useSingleServer().setAddress("redis://localhost:6379").setPassword("");
```

### 数据库初始化

执行`src/main/resources/db/review.sql`脚本初始化数据库结构和数据

### 启动项目

```bash
mvn spring-boot:run
```

## 接口文档

### 商铺相关接口

- `GET /shop/{id}`：查询商铺详情（带缓存）
- `PUT /shop`：更新商铺信息（更新缓存）
- `GET /shop/type/{typeId}`：根据类型查询商铺
- `GET /shop/type/{typeId}/geo`：根据类型和地理位置查询商铺

### 优惠券与秒杀接口

- `GET /voucher/list`：查询优惠券列表
- `GET /voucher/seckill/list`：查询秒杀优惠券列表
- `POST /voucher-order/seckill/{voucherId}`：秒杀优惠券

### 用户相关接口

- `POST /user/login`：用户登录
- `POST /user/sign`：用户签到
- `GET /user/sign/count`：查询用户签到统计

### 社交功能接口

- `POST /blog/like/{id}`：点赞博客
- `GET /blog/liked/{id}`：查询博客点赞用户
- `POST /follow/{id}`：关注用户
- `DELETE /follow/{id}`：取消关注
- `GET /follow/common/{id}`：查询共同关注
- `POST /blog`：发布博客
- `GET /blog/of/follow`：查询关注的博客

## 总结

Redis Review Platform项目全面展示了Redis在实际业务场景中的应用，通过合理使用Redis的各种数据结构和特性，解决了高并发场景下的性能问题和一致性问题。项目实现了：

1. 高性能的缓存系统，解决了缓存穿透、击穿、雪崩等常见问题
2. 高并发的秒杀系统，使用Lua脚本和消息队列保证了系统的稳定性和一致性
3. 丰富的社交功能，利用Redis的数据结构实现了高效的点赞、关注和feed流功能
4. 地理位置服务，通过GEO数据结构实现了附近商铺的高效查询
5. 分布式系统中的关键技术，如分布式锁、全局唯一ID生成等

### 未来扩展方向

该项目具有良好的扩展性，可以进一步扩展以下功能：

- 引入Redis Cluster实现高可用Redis集群
- 添加更多社交功能，如评论、私信等
- 实现更复杂的推荐系统
- 引入消息队列实现异步通信
- 添加监控和日志系统

Redis Review Platform项目展示了如何在Spring Boot项目中集成和使用Redis来提升系统性能和并发能力，是Redis实战学习的理想案例。