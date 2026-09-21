package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private ExecutorService pool = Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //实现任意对象存入Redis方法
    public <ID> void set(String keyPrefix, ID id, Object value, Long time , TimeUnit unit)
    {
        String key = keyPrefix+id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);

    }
    //实现任意对象存入Redis,附上过期时间
    public<ID> void setWithLogicExpire(String keyPrefix, ID id, Object value, Long time , TimeUnit unit)
    {
        String key = keyPrefix+id;
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //获取任意对象
    public <R,ID> R get(String keyPrefix, ID id, Class<R> type, Function<ID,R> dataFallBack,Long time , TimeUnit unit)
    {
        String key = keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(json))
        {
            //找到且不是空对象了,直接返回
            return JSONUtil.toBean(json,type);
        }
        if(json!=null)
        {
            //空对象,返回空值
            return null;
        }

        //redis 不存在,查数据库

        R r = dataFallBack.apply(id);

        if(r==null)
        {
            //为空,存空对象
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //不为空,存入redis

        this.set(keyPrefix,id,r,time,unit);
        return r;


    }

    public <R,ID> R getWithLogicExpire
            (String keyPrefix, ID id, Class<R> type, Function<ID,R> dataFallBack,Long time , TimeUnit unit)
    {
        String key = keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if(json==null)
        {
            //不存在直接返回
            return null;
        }

        //存在,判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Object data = redisData.getData();

        if(expireTime.isAfter(LocalDateTime.now()))
        {
            //没有过期,返回对象
            return  JSONUtil.toBean((JSONObject) data, type);
        }

        //过期,获取锁,开启新线程

        if(tryLock(LOCK_KEY+keyPrefix+id))
        {

            //获取成功,执行线程任务
            pool.submit(()->{
                try {
                    R r = dataFallBack.apply(id);
                    this.setWithLogicExpire(keyPrefix,id,r,time,unit);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
                finally {
                    releaseLock(LOCK_KEY+keyPrefix+id);
                }

            });

        }


        return JSONUtil.toBean((JSONObject) data, type);

    }
    public Boolean tryLock(String key)
    {

        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    public void releaseLock(String key)
    {


        stringRedisTemplate.delete(key);
    }





}
