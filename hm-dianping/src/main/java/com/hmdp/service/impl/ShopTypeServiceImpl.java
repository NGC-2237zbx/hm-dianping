package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.coyote.Response;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryShopType() throws JsonProcessingException {
        //从redis查询全部商户类型信息
        String shopTypeJsaon = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_KEY);
        //判断是否为空
        if(StrUtil.isNotBlank(shopTypeJsaon)){
            //不为空，返回
            ObjectMapper mapper = new ObjectMapper();
            List<ShopType> typeList = mapper.readValue(shopTypeJsaon, new TypeReference<List<ShopType>>() {});
            return Result.ok(typeList);
        }
        //不存在从数据库查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //不存在返回错误信息
        if(typeList==null){
            return Result.fail("不存在商户类型信息！");
        }
        //存在将信息存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_KEY, JSONUtil.toJsonStr(typeList),CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(typeList);
    }
}
