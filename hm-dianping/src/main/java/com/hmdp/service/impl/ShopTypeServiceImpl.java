package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.logging.log4j.util.Strings;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

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
    public List<ShopType> queryTypeList() {

        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        if(Strings.isNotBlank(s))
        {
            List<ShopType> list = JSONUtil.toList(s, ShopType.class);
            return list;
        }

        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        if(shopTypes.isEmpty())
        {
            return  shopTypes;
        }

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypes),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);


        return shopTypes;
    }
}
