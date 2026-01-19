# Redis Review Platform

[中文版本](./README.md)

## Project Introduction

Redis Review Platform is a comprehensive review platform built with Spring Boot and Redis. It leverages Redis to implement high-performance, high-concurrency business scenarios including shop search, coupon flash sale, geolocation search, user following, and like functionality. The project fully utilizes various features of Redis to solve performance bottlenecks and concurrency issues in real-world business scenarios.

## Technology Stack

- **Backend Framework**: Spring Boot
- **ORM Framework**: MyBatis Plus
- **Cache Database**: Redis 6.x
- **Distributed Lock**: Redisson
- **Serialization Tool**: Hutool JSON
- **Database**: MySQL 8.x

## Core Features and Redis Applications

### 1. Shop Cache System

#### Cache Penetration Protection
- **Implementation**: Null value caching strategy
- **Redis Application**: Cache non-existent shop IDs as empty strings with a short expiration time (2 minutes)
- **Code Location**: `ShopServiceImpl.queryWithPenetrateGuard()` and `CacheClient.queryWithPenetrateGuard()`

#### Cache Breakdown Protection
- **Implementation**:
  - Mutex lock: Implement distributed lock based on Redis `setnx`
  - Logical expiration: Set logical expiration time for cache and rebuild cache asynchronously after expiration
- **Redis Application**:
  - Mutex lock: Use `setIfAbsent` to implement distributed lock
  - Logical expiration: Store objects with expiration time in Redis
- **Code Location**:
  - Mutex lock: `ShopServiceImpl.queryWithMutex()` and `CacheClient.queryWithMutex()`
  - Logical expiration: `ShopServiceImpl.queryWithLogicalExpire()` and `CacheClient.queryWithLogicalExpire()`

#### Cache Update Strategy
- **Implementation**: Dual-write consistency (update database first, then delete cache)
- **Redis Application**: Use `delete` command to delete cache
- **Code Location**: `ShopServiceImpl.update()`

### 2. High-Concurrence Flash Sale System

#### Flash Sale Core Process
- **Implementation**: Lua script + Redis Stream
- **Redis Application**:
  - Use Lua script to ensure atomicity of inventory check, deduction, and order creation
  - Use Redis Stream to implement asynchronous order processing
- **Code Location**:
  - Lua script: `src/main/resources/seckill.lua`
  - Flash sale service: `VoucherOrderServiceImpl.seckillVoucher()`
  - Asynchronous processing: `VoucherOrderServiceImpl.VoucherOrderHandler`

#### Duplicate Order Prevention
- **Implementation**: Based on Redis Set data structure
- **Redis Application**: Use `SISMEMBER` command to check if user has already placed an order
- **Code Location**: Line 29 of `seckill.lua` script

#### Inventory Deduction
- **Implementation**: Redis atomic operation
- **Redis Application**: Use `incrby` command to atomically deduct inventory
- **Code Location**: Line 35 of `seckill.lua` script

### 3. Geolocation Search

#### Nearby Shop Query
- **Implementation**: Redis GEO data structure
- **Redis Application**:
  - Use `GEOADD` to add shop geolocation
  - Use `GEORADIUS` or `GEOSEARCH` to query nearby shops
- **Code Location**: `ShopServiceImpl.queryShopByType()`

### 4. User Social Features

#### Follow and Unfollow
- **Implementation**: Redis Set data structure
- **Redis Application**: Use Set to store user following relationships
- **Code Location**: `FollowController`

#### Like Functionality
- **Implementation**: Redis Sorted Set data structure
- **Redis Application**:
  - Use Sorted Set to store liked users and like timestamps, score is like timestamp
  - Support displaying liked users sorted by like time
  - Support atomic like/unlike operations
- **Core Process**:
  1. Check if user has already liked (query if user ID exists in Sorted Set)
  2. If already liked, cancel like and decrease like count
  3. If not liked, add like and increase like count
  4. Add/remove user ID from Sorted Set
- **Code Location**: `BlogServiceImpl.likeBlog()` and `BlogServiceImpl.isBlogLiked()`

#### Follow Feed Stream
- **Implementation**: Redis Sorted Set data structure
- **Redis Application**:
  - Use Sorted Set to store user follow feed, score is publish timestamp
  - Support reverse chronological query
  - Support scroll pagination loading
- **Core Process**:
  1. When user publishes a blog, push to all followers' feed streams
  2. Use `ZREVRANGEBYSCORE` to implement reverse chronological scroll pagination query
  3. Support offset handling for same timestamps
- **Code Location**: `BlogServiceImpl.saveBlog()` and `BlogServiceImpl.queryBlogOfFollow()`

### 5. User Check-in System

#### Check-in Functionality
- **Implementation**: Redis BitMap data structure
- **Redis Application**: Use BitMap to store user check-in records
- **Code Location**: `UserController.sign()`

#### Continuous Check-in Statistics
- **Implementation**: Bitwise operations on Redis BitMap
- **Redis Application**: Use `BITFIELD` and `BITCOUNT` commands to count continuous check-in days
- **Code Location**: `UserController.getSignCount()`

## Redis Configuration and Utility Classes

### Redisson Configuration
- **Configuration File**: `RedissonConfig.java`
- **Function**: Configure Redisson client for distributed locks and other advanced Redis features

### Redis Utility Classes

### CacheClient
Encapsulates various Redis cache operations, providing a unified cache access interface:
- Basic cache setting and getting
- Logical expiration cache setting
- Cache query with penetration protection
- Cache query with mutex lock
- Cache query with logical expiration

### RedisIdWorker
Redis-based global unique ID generator with high performance and high availability features:
- **Implementation Principle**: Uses "timestamp + sequence" combination
  - Timestamp: Seconds since fixed start time
  - Sequence: Redis auto-increment, 32-bit length
- **Core Algorithm**: `(currentTimestamp - startTime) << 32 | sequence`
- **Application Scenarios**: Order IDs, coupon IDs, etc.
- **Code Location**: `RedisIdWorker.nextId()`

### SimpleRedisLock
Redis-based distributed lock that supports cluster environments:
- **Implementation Principle**:
  - Acquire lock: Use `setIfAbsent` command to set key-value pair with expiration time
  - Thread identification: Use "server ID + thread ID" to ensure lock uniqueness
  - Unlock mechanism: Use Lua script to ensure atomicity of unlocking and prevent deleting other threads' locks
- **Core Features**:
  - Support automatic lock release with expiration time
  - Prevent accidental lock deletion
  - Support cluster environments
- **Application Scenarios**: Cache rebuilding, flash sales, etc.
- **Code Location**: `SimpleRedisLock.tryLock()` and `SimpleRedisLock.unlock()`

## User Authentication with Redis

The project implements an efficient user authentication mechanism using Redis:

- **Token Management**:
  - Generate token and store in Redis after user login
  - Use `LOGIN_USER_KEY + token` as key and user information as value (Hash structure)
  - Set token expiration time (36000 seconds, i.e., 10 hours)

- **Token Refresh**:
  - `RefreshTokenInterceptor` automatically refreshes token validity
  - Extend token expiration time when user accesses authenticated interfaces
  - Avoid token expiration during user activity

- **User Information Storage**:
  - Use `ThreadLocal` to store current login user information for improved access efficiency
  - `LoginInterceptor` verifies user login status

## Lua Script Applications

The project extensively uses Lua scripts to ensure atomicity of Redis operations:

### Flash Sale Script (seckill.lua)
- **Core Function**: Ensure atomicity of inventory check, deduction, and order creation
- **Execution Flow**:
  1. Check if inventory is sufficient
  2. Check if user has already placed an order
  3. Deduct inventory
  4. Record user order information
  5. Send message to Redis Stream

### Unlock Script (unlock.lua)
- **Core Function**: Ensure atomicity of distributed lock unlocking
- **Execution Flow**:
  1. Get thread identification in the lock
  2. Compare with current thread identification
  3. Delete lock if they match, otherwise return directly

### Advantages of Lua Scripts
- Reduce network overhead: Merge multiple Redis commands into one request
- Ensure atomicity: All commands in the script are executed as a single atomic operation
- Improve performance: Reduce network latency for command execution

## Project Structure

```
src/main/java/com/review/
├── config/              # Configuration classes
│   ├── MvcConfig.java
│   ├── MybatisConfig.java
│   ├── RedissonConfig.java
│   └── WebExceptionAdvice.java
├── controller/          # Controllers
│   ├── BlogCommentsController.java
│   ├── BlogController.java
│   ├── FollowController.java
│   ├── ShopController.java
│   ├── ShopTypeController.java
│   ├── UploadController.java
│   ├── UserController.java
│   ├── VoucherController.java
│   └── VoucherOrderController.java
├── dto/                 # Data transfer objects
│   ├── LoginFormDTO.java
│   ├── Result.java
│   ├── ScrollResult.java
│   └── UserDTO.java
├── entity/              # Entity classes
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
├── mapper/              # Mapper interfaces
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
├── service/             # Service layer
│   ├── impl/           # Service implementations
│   └── *Service.java   # Service interfaces
├── utils/              # Utility classes
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
└── DianPingApplication.java  # Application entry class
```

## Redis Constant Definitions

Core Redis key names and expiration times are defined in `RedisConstants.java`:

| Constant Name | Key Format | Expiration Time | Purpose |
|--------------|-----------|----------------|---------|
| CACHE_SHOP_KEY | cache:shop:{id} | 30 minutes | Shop cache |
| LOCK_SHOP_KEY | lock:shop:{id} | 10 minutes | Shop cache rebuild lock |
| SECKILL_STOCK_KEY | seckill:stock:{id} | - | Flash sale inventory |
| BLOG_LIKED_KEY | blog:liked:{id} | - | Blog like records |
| FEED_KEY | feed:{id} | - | User follow feed |
| SHOP_GEO_KEY | shop:geo:{id} | - | Shop geolocation |
| USER_SIGN_KEY | sign:{id} | - | User check-in records |

## Redis Data Structure Usage Summary

| Data Structure | Application Scenarios | Core Commands |
|---------------|----------------------|--------------|
| String | Shop cache, flash sale inventory, distributed lock | `SET`, `GET`, `INCRBY`, `SETNX` |
| Hash | User information storage | `HSET`, `HGET`, `HENTRIES` |
| List | - | - |
| Set | Duplicate order prevention, user following | `SISMEMBER`, `SADD` |
| Sorted Set | Blog like sorting, follow feed stream | `ZADD`, `ZRANGE`, `ZREVRANGEBYSCORE`, `ZSCORE` |
| Geo | Nearby shop query | `GEOADD`, `GEOSEARCH` |
| BitMap | User check-in | `SETBIT`, `GETBIT`, `BITCOUNT`, `BITFIELD` |
| Stream | Flash sale order asynchronous processing | `XADD`, `XREADGROUP`, `XACK` |

## Redis Integration with Spring Data Redis

The project uses Spring Data Redis as the Redis client, providing rich Redis operation interfaces:

- **Automatic Configuration**: Spring Boot automatically configures Redis connection pool and client
- **Template Classes**:
  - `StringRedisTemplate`: For string type Redis operations
  - `RedisTemplate`: For object type Redis operations
- **Advanced Feature Support**:
  - Redis Stream message queue functionality
  - Geo data structure geolocation query
  - Lua script execution support
  - Redis connection pool management

## Performance Optimization Highlights

1. **High-Performance Cache Design**:
   - Multi-layer cache strategy to solve cache penetration, breakdown, and avalanche issues
   - Asynchronous cache rebuilding to avoid blocking user requests
   - Cache and database consistency maintenance

2. **High-Concurrence Flash Sale System**:
   - Lua script ensures atomicity of inventory deduction
   - Redis Stream implements asynchronous order processing
   - Distributed lock prevents overselling

3. **Efficient Social Features**:
   - Redis Sorted Set implements like sorting
   - Redis-based feed stream push mechanism
   - Scroll pagination loading for optimized performance

4. **Geolocation Service**:
   - Redis Geo implements nearby shop query
   - Efficient geolocation indexing

5. **Distributed System Design**:
   - Redis implements global unique ID generation
   - Redis-based distributed lock
   - Token bucket algorithm for traffic control

6. **Security and Stability**:
   - Redis-based token refresh mechanism
   - Distributed lock prevents concurrency issues
   - Comprehensive exception handling mechanism

## Quick Start

### Environment Requirements

- JDK 1.8+
- MySQL 8.0+
- Redis 6.0+

### Configuration Files

1. Modify database and Redis configuration in `application.yml`:

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

2. Modify Redis address configuration in `RedissonConfig.java`:

```java
config.useSingleServer().setAddress("redis://localhost:6379").setPassword("");
```

### Database Initialization

Execute `src/main/resources/db/review.sql` script to initialize database structure and data.

### Start the Project

```bash
mvn spring-boot:run
```

## API Documentation

### Shop-related APIs

- `GET /shop/{id}`: Query shop details (with cache)
- `PUT /shop`: Update shop information (update cache)
- `GET /shop/type/{typeId}`: Query shops by type
- `GET /shop/type/{typeId}/geo`: Query shops by type and geolocation

### Coupon and Flash Sale APIs

- `GET /voucher/list`: Query coupon list
- `GET /voucher/seckill/list`: Query flash sale coupon list
- `POST /voucher-order/seckill/{voucherId}`: Flash sale coupon

### User-related APIs

- `POST /user/login`: User login
- `POST /user/sign`: User check-in
- `GET /user/sign/count`: Query user check-in statistics

### Social Feature APIs

- `POST /blog/like/{id}`: Like blog
- `GET /blog/liked/{id}`: Query blog liked users
- `POST /follow/{id}`: Follow user
- `DELETE /follow/{id}`: Unfollow user
- `GET /follow/common/{id}`: Query common follows
- `POST /blog`: Publish blog
- `GET /blog/of/follow`: Query follow feed

## Summary

Redis Review Platform comprehensively demonstrates the application of Redis in real-world business scenarios. By reasonably using various data structures and features of Redis, it solves performance problems and consistency issues in high-concurrency scenarios. The project implements:

1. High-performance cache system, solving common problems like cache penetration, breakdown, and avalanche
2. High-concurrency flash sale system, using Lua scripts and message queues to ensure system stability and consistency
3. Rich social features, leveraging Redis data structures to implement efficient like, follow, and feed stream functionality
4. Geolocation service, implementing efficient nearby shop query through GEO data structure
5. Key technologies in distributed systems, such as distributed locks and global unique ID generation

### Project Value and Learning Significance

This project is an excellent case for learning Redis practical applications, with the following learning values:

- **Comprehensive Redis Application**: Covers all major data structures and features of Redis
- **Real Business Scenarios**: Based on real review platform business, close to actual development needs
- **Performance Optimization Practice**: Demonstrates how to use Redis to solve performance problems in high-concurrency scenarios
- **Distributed System Design**: Contains core distributed system technologies such as distributed locks and global unique IDs
- **Code Quality and Maintainability**: Adopts good code structure and design patterns

By learning this project, developers can deeply understand how Redis is applied in actual projects, master the design and implementation methods of high-performance, high-concurrency systems, and improve distributed system development capabilities.

### Future Expansion Directions

The project has good scalability and can be further expanded with the following features:

- Introduce Redis Cluster to implement high-availability Redis cluster
- Add more social features such as comments and private messages
- Implement more complex recommendation systems
- Introduce message queues for asynchronous communication
- Add monitoring and logging systems

Redis Review Platform demonstrates how to integrate and use Redis in Spring Boot projects to improve system performance and concurrency capabilities, making it an ideal case for Redis practical learning.