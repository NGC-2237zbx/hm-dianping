package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    //将任意java对象序列化为json并存储在String类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //将任意java对象序列化为json并存储在String类型的key中，并且可以设置逻辑过期时间，用于解决缓存击穿问题
    public void setWithLogicalExpire(String key,Object value,Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //根据指定的key查询缓存，并且反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time,TimeUnit unit){
        String key=keyPrefix+id;
        //从redis查询商铺缓存
        String json=stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isNotBlank(json)){
            return  JSONUtil.toBean(json, type);
        }
        if (json!=null)
        {
            return null;
        }
        //不存在根据id查询数据库
        R r=dbFallBack.apply(id);
        //不存在返回错误
        if (r==null){
            // 缓存空对象避免缓存穿透
            this.set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在写入redis
        this.set(key,r,time,unit);
        //返回
        return r;
    }

    //根据指定的key查询缓存，并且反序列化为指定类型，利用逻辑过期解决缓存击穿问题
    //创建线程池
    private static final ExecutorService CACHE_REBULID_EXECUTOR= Executors.newFixedThreadPool(10);

    //解决缓存击穿问题（逻辑过期）
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time, TimeUnit unit,String lockPrefix){
        String key=keyPrefix+id;
        //从redis查询商铺缓存
        String Json=stringRedisTemplate.opsForValue().get(key);
        //判断是否命中
        if(StrUtil.isBlank(Json)){
            //未命中返回空
            return null;
        }
        //命中,把json反序列化
        RedisData redisdata = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisdata.getData(), type);
        LocalDateTime expireTime=redisdata.getExpireTime();
        //判断逻辑是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }
        //过期则需要重建缓存
        //获取互斥锁
        String lockkey=lockPrefix+id;
        boolean islock = tryLock(lockkey);
        //判断是否获取成功
        if(islock) {
            //成功则再次检查redis缓存是否过期，做DoubleCheck（防止在获取锁之前其他线程刚好完成缓存重建并释放锁的情况下该线程重复重建缓存）
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期，重新从redis查询缓存，返回
                String JsonNew=stringRedisTemplate.opsForValue().get(key);
                RedisData redisdataNew = JSONUtil.toBean(JsonNew, RedisData.class);
                R rNew = JSONUtil.toBean((JSONObject) redisdataNew.getData(), type);
                return rNew;
            }
            //还是过期则开启独立线程重建缓存
            CACHE_REBULID_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockkey);
                }
            });
        }
        //失败则返回过期的缓存
        return r;
    }
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(String key){
        Boolean f = stringRedisTemplate.delete(key);
    }
}
