package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
@Slf4j
@Service
public  class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        //缓存空对象来解决缓存穿透
        //Shop shop = queryWithPassThrough(id);

        Shop shop = cacheClient.get(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //互斥锁来解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //缓存过期来解决缓存击穿
        //Shop shop = queryWithLogicExpire(id);
        return Result.ok(shop);
    }

//    public Shop queryWithLogicExpire(Long id)
//    {
//        String key =  CACHE_HOT_SHOP_KEY + id;
//        String lockKey = LOCK_HOT_SHOP_KEY+id;
//
//        //Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);
//
//        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
//
//        //从redis缓存中查找
//        if(StrUtil.isBlank(redisDataJson))
//        { //不存在 返回空
//            return null;
//        }
//
//        //存在,判断是否过期
//
//        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if(expireTime.isAfter(LocalDateTime.now()))
//        {
//            //没过期,返回shop
//            Object data = redisData.getData();
//            Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
//            return shop;
//        }
//        //过期,获取互斥锁
//        if(tryLock(lockKey))
//        {
//            //获取成功,开启新线程修改过期时间
//            pool.submit(()->{
//                try {
//                    Thread.sleep(1000);
//                    saveShop2Redis(id,30L,TimeUnit.SECONDS);
//                }
//                catch (Exception e)
//                {
//                    throw new RuntimeException(e);
//                }
//                finally {
//                    releaseLock(lockKey);
//                }
//            });
//            //原线程返回旧数据
//        }
//        //获取失败,返回旧数据
//        Object data = redisData.getData();
//        Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
//        return shop;
//
//    }
//
//
//
//    public Shop queryWithPassThrough(Long id)
//    {
//        String key =  CACHE_SHOP_KEY + id;
//
//        //从redis缓存中查找
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//
//        if(shopJson!=null)
//        {
//            //存在,判断是否是空对象
//            if(StrUtil.isBlank(shopJson))
//            {
//                return null ;
//            }
//
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//
//
//        }
//
//
//        //不存在,数据库查询
//
//        Shop shop = getById(id);
//
//
//        if(shop==null)
//        {
//            //数据库没查到,缓存空对象到redis
//            //stringRedisTemplate.opsForHash().put(key,"is_empty","true");
//            stringRedisTemplate.opsForValue().set(key,"");
//            stringRedisTemplate.expire(key,CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//
//
//        // 查到，写入缓存，并返回值
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
//        stringRedisTemplate.expire(key,CACHE_SHOP_TTL,TimeUnit.MINUTES);
//
//        return shop;
//    }

//    public Shop queryWithMutex(Long id)
//    {
//        String key =  CACHE_SHOP_KEY + id;
//
//        String lockKey = LOCK_SHOP_KEY+id;
//        //从redis缓存中查找
//
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //判断是否存在
//        if(shopJson!=null)
//        {
//            //存在,判断是否是空对象
//            if(StrUtil.isBlank(shopJson))
//            {
//                return null ;
//            }
//
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//
//
//        }
//
//
//        //不存在,获取互斥锁
//
//        try{
//            while (true)
//            {
//                if (tryLock(lockKey)) {
//                    //获取成功
//                    try{
//                        //再次查缓存,做doubleCheck
//                        shopJson = stringRedisTemplate.opsForValue().get(key);
//                        if(shopJson!=null)
//                        {
//                            //存在,判断是否是空对象
//                            if(StrUtil.isBlank(shopJson))
//                            {
//                                return null ;
//                            }
//
//                            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//                            return shop;
//
//
//                        }
//                        //缓存不存在,查数据库
//                        Shop shop = getById(id);
//
//                        //模拟线程延时
//                        //Thread.sleep(2000);
//                        if(shop==null)
//                        {
//                            //stringRedisTemplate.opsForHash().put(key,"is_empty","true");
//                            stringRedisTemplate.opsForValue().set(key,"");
//                            stringRedisTemplate.expire(key,CACHE_NULL_TTL,TimeUnit.SECONDS);
//                            return null;
//                        }
//
//                        // 查到，写入缓存，并返回值
//                        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
//                        stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
//                        return shop;
//                    }finally {
//                        releaseLock(lockKey);
//                    }
//
//                }
//                //获取不成功,睡眠加循环实现等待
//                try {
//                    Thread.sleep(1000);
//
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        }
//        catch (Exception e){
//            throw new RuntimeException(e);
//        }
//
//
//    }

//    public Boolean tryLock(String key)
//    {
//
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//
//        return BooleanUtil.isTrue(flag);
//    }
//
//    public void releaseLock(String key)
//    {
//
//
//        stringRedisTemplate.delete(key);
//    }

    public void saveShop2Redis(Long id ,Long time ,TimeUnit unit)
    {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(CACHE_HOT_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop) {

        if(getById(shop.getId())==null)
        {
            return Result.fail("店铺id不能为空!");
        }

        updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());

        return Result.ok();

    }
}
