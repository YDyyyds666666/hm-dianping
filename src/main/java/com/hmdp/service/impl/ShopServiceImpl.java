package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryByID(Long id) {

        //利用工具类解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //利用工具类逻辑锁解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES,LOCK_SHOP_KEY);
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //用互斥锁解决缓存击穿
        //Shop shop=queryWithMutex(id);
        //用逻辑过期解决缓存击穿
//        Shop shop=queryWithLogicalExpire(id);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    /** 使用逻辑过期解决缓存击穿 */
    public Shop queryWithLogicalExpire(Long id) {

        String key=CACHE_SHOP_KEY + id;
        // 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 未缓存命中
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        // TODO 命中 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //  5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            // 5.1 未过期 返回店铺信息
            return shop;
        }
        // 5.2 已过期 缓存重建
        String lockKey=LOCK_SHOP_KEY+id;
        // 6 尝试缓存重建
        //6.1 获取互斥锁
        boolean isLock=tryLock(lockKey);
        //6.2 获取锁成功
        if(isLock){
            // 6.3  TODO 成功，开启独立线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id, 5*60L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        // 7. 不管是否成功获得锁，都返回过期缓存
        return shop;
    }



    /** 解决缓存穿透的基础上，使用互斥锁解决了缓存击穿 */
    public Shop queryWithMutex(Long id) {
        String key=CACHE_SHOP_KEY + id;
        // 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 缓存命中
        if(StrUtil.isNotBlank(shopJson)){
            //存在且有商铺数据才为true
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){
            return null;
        }
        // TODO 未命中 实现缓存重建
        String lockKey="lock:shop:"+id;
        Shop shop = null;
        try {
            //获取互斥锁
            boolean isLock = tryLock(lockKey);
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);    //使用了return递归调用
            }
            shop = getById(id);
            //Thread.sleep(200);   //模拟延迟
            //数据库中不存在
            if(shop == null){
                //TODO 将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //数据库中存在  重建线程
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
        }
        return shop;
    }

    /** 通过缓存空对象，解决了缓存穿透 */
    public Shop queryWithPassThrough(Long id) {
        String key=CACHE_SHOP_KEY + id;
        // 查询redis缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //存在且有商铺数据
        if(StrUtil.isNotBlank(shopJson)){
            //存在且有商铺数据才为true
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // TODO 考虑空值的的情况
        if(shopJson!=null){
            return null;
        }
        //缓存 不存在
        Shop shop = getById(id);
        //数据库中不存在
        if(shop == null){
            //TODO 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //数据库中存在  重建缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag= stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /** 添加逻辑缓存时间 */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop=getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }
}
