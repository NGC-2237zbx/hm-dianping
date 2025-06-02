package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.map.MapUtil;
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
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
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
    public Result queryById(Long id) {
        //解决缓存穿透问题
        //Shop shop=queryWithPassThrough(id);
        //互斥锁解决缓存击穿问题
        //Shop shop=queryWithMutex(id);
        //逻辑过期解决缓存击穿问题
        //Shop shop=queryWithLogicExpire(id);

       /* Shop shop=cacheClient.
                queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        */
        Shop shop=cacheClient.
                queryWithLogicExpire(CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),10L,TimeUnit.SECONDS,LOCK_SHOP_KEY);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }
        //返回
        return Result.ok(shop);
    }
    /*//解决缓存穿透（缓存空值）问题
    public Shop queryWithPassThrough(Long id){
        //从redis查询商铺缓存
        Map<Object,Object> shopmap=stringRedisTemplate.opsForHash().entries(CACHE_SHOP_KEY+id);
        //判断是否存在
        if(!MapUtil.isEmpty(shopmap)){
            //存在先判断是否是空缓存
            if ("true".equals(shopmap.get("isNull"))){
                return null;
            }
            //不是直接返回
            Shop shop = BeanUtil.toBean(shopmap, Shop.class);
            return shop;
        }
            //不存在根据id查询数据库
            Shop shop = getById(id);
            //不存在返回错误
            if (shop==null){
                // 缓存空对象避免缓存穿透
                Map<String, String> emptyShop = new HashMap<>();
                emptyShop.put("isNull", "true");
                stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY+id,emptyShop);
                stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在写入redis
            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY+id,BeanUtil.beanToMap(shop,new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true).
                            setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
            ));
            stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //返回
        return shop;
    }*/
    //既解决缓存穿透问题也解决缓存击穿问题（互斥锁），这两个问题不会同时发生
   /* public Shop queryWithMutex(Long id){
        //从redis查询商铺缓存
        Map<Object,Object> shopmap=stringRedisTemplate.opsForHash().entries(CACHE_SHOP_KEY+id);
        //判断是否存在
        if(!MapUtil.isEmpty(shopmap)){
            //存在,先判断是否是空缓存
            if ("true".equals(shopmap.get("isNull"))){
                return null;
            }
            //不是直接返回
            Shop shop = BeanUtil.toBean(shopmap, Shop.class);
            return shop;
        }
        //不存在获取互斥锁
        String lockkey=LOCK_SHOP_KEY+id;
        Shop shop=null;
        try {
            boolean islock = tryLock(lockkey);
            //判断是否成功
            if (!islock){
                //失败则休眠并且重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功则根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            //不存在返回错误
            if (shop==null){
                // 缓存空对象避免缓存穿透
                Map<String, String> emptyShop = new HashMap<>();
                emptyShop.put("isNull", "true");
                stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY+id,emptyShop);
                stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在写入redis
            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY+id,BeanUtil.beanToMap(shop,new HashMap<>(),
                    CopyOptions.create().setIgnoreNullValue(true).
                            setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
            ));
            stringRedisTemplate.expire(CACHE_SHOP_KEY+id,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(lockkey);
        }
        //返回
        return shop;
    }*/

    //创建线程池
    // private static final ExecutorService CACHE_REBULID_EXECUTOR= Executors.newFixedThreadPool(10);
/*
    //解决缓存击穿问题（逻辑过期）
    public Shop queryWithLogicExpire(Long id){
        //从redis查询商铺缓存
        String shopJson=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //判断是否命中
        if(StrUtil.isBlank(shopJson)){
            //未命中返回空
            return null;
        }
        //命中,把json反序列化
        RedisData redisdata = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisdata.getData(), Shop.class);
        LocalDateTime expireTime=redisdata.getExpireTime();
        //判断逻辑是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
           //未过期，直接返回
            return shop;
        }
        //过期则需要重建缓存
        //获取互斥锁
        String lockkey=LOCK_SHOP_KEY+id;
        boolean islock = tryLock(lockkey);
        //判断是否获取成功
        if(islock) {
            //成功则再次检查redis缓存是否过期，做DoubleCheck（防止在获取锁之前其他线程刚好完成缓存重建并释放锁的情况下该线程重复重建缓存）
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期，重新从redis查询缓存，返回
                String shopJsonNew=stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
                RedisData redisdataNew = JSONUtil.toBean(shopJsonNew, RedisData.class);
                Shop shopNew = JSONUtil.toBean((JSONObject) redisdataNew.getData(), Shop.class);
                return shopNew;
            }
            //还是过期则开启独立线程重建缓存
            CACHE_REBULID_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShopToRedis(id,10L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockkey);
                }
            });
        }
        //失败则返回过期的缓存
        return shop;
    }*/
  /*  public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(String key){
        Boolean f = stringRedisTemplate.delete(key);
    }
    public void saveShopToRedis(Long id,Long expireSecondes) throws InterruptedException {
        //查询店铺数据
        Shop shop=getById(id);
        Thread.sleep(200);
        //将店铺封装为RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        LocalDateTime expireTime=LocalDateTime.now().plusSeconds(expireSecondes);
        redisData.setExpireTime(expireTime);
        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    @Transactional
    public Result update(Shop shop) {
        //更新数据库
        updateById(shop);
        //删除redis缓存
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId()) ;
        return Result.ok();
    }
}
