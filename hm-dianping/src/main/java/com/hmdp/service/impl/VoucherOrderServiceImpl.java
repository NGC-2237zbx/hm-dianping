package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Override
    public Result seckillVoucher(Long voucherId) {
       //查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
       //判断是否在活动期间
        LocalDateTime beginTime = voucher.getBeginTime();
        if(beginTime.isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //判断是否还有库存
        Integer stock = voucher.getStock();
        if(stock<1){
            return Result.fail("库存不足");
        }
        Long userId= UserHolder.getUser().getId();
        //针对用户加锁（一个用户一个锁）
        synchronized (userId.toString().intern()){
            //得到当前对象的代理对象（因为Spring 的事务是通过 AOP 代理机制 实现的，如果通过当前对象调用createVoucherOrder方法，事务会失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            //调用创建订单的方法
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //实现一人一单
        Long userId= UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(count>0){
            return Result.fail("用户已经下过单了");
        }
        Boolean success=seckillVoucherService.update().setSql("stock=stock-1")//set stock=stock-1
                .eq("voucher_id",voucherId)
                //.eq("stock",voucher.getStock())//where id=? and sock=?
                .gt("stock",0)//where sock >0
                .update();
        if(!success){
            return Result.fail("库存不足");
        }
        //创建订单
        VoucherOrder voucherOrder=new VoucherOrder();
        //生成订单号id
        long orderid = redisIdWorker.nextId("order:");
        voucherOrder.setId(orderid);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //用户id
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(orderid);
    }
}
