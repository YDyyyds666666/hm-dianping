package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.hmdp.utils.RedisConstants.*;


/**
 *  缓存工具类封装
 *  set函数：                       设定指定TTL的缓存
 *  setWithLogicalExpire函数：      设定指定逻辑过期时间的缓存
 *  queryWithPassThrough函数：      查询函数： 缓存空对象法解决缓存穿透
 *  queryWithLogicalExpire函数：    查询函数： 逻辑过期法解决缓存击穿
 *  注意：    逻辑过期法只针对热点数据，所以不考虑缓存不存在的情况 热点数据需要提前加入缓存
 * */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    /**
     * 调用时需要指定：
     * 缓存前缀+id+返回对象类型+数据库查询函数+缓存时间
     * */
    public <R,ID>R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        String key=keyPrefix + id;
        // 查询redis缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在且有商铺数据
        if(StrUtil.isNotBlank(json)){
            //存在且有商铺数据才为true
            return JSONUtil.toBean(json,type);
        }
        // TODO 考虑空值的的情况
        if(json!=null){
            return null;
        }
        //缓存 不存在
        R r = dbFallback.apply(id);
        //数据库中不存在
        if(r == null){
            //TODO 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在 重建缓存
        this.set(key,r,time,unit);
        return r;
    }
    /**
     * 调用时需要指定：
     * 缓存前缀+id+返回对象类型+数据库查询函数+缓存时间+缓存互斥锁前缀
     * */
    public <R,ID>R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit, String lockPreFix) {
        String key=keyPrefix + id;
        // 查询redis缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未缓存命中
        if(StrUtil.isBlank(json)){
            return null;
        }
        // TODO 命中 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //  5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1 未过期 返回店铺信息
            return r;
        }
        // 5.2 已过期 缓存重建
        String lockKey=lockPreFix+id;
        // 6 尝试缓存重建
        //6.1 获取互斥锁
        boolean isLock=tryLock(lockKey);
        //6.2 获取锁成功
        if(isLock){
            // 6.3  TODO 成功，开启独立线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1=dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        // 7. 不管是否成功获得锁，都返回过期缓存
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    private boolean tryLock(String key){
        Boolean flag= stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
