package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshInterceptor implements HandlerInterceptor {

    //自己定义的类无法实现自动注入(非spring类构建)

    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //获取token

        String token = request.getHeader("authorization");

        if (StrUtil.isBlank(token)) {

            return true;
        }

        //获取用户
        String key =  RedisConstants.LOGIN_USER_KEY + token;

        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);

        //匹配用户
        if(entries.isEmpty())
        {
            return true;
        }

        //符合则放行,保存到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);

        UserHolder.saveUser(userDTO);

        //有请求,说明用户在操作,刷新有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        UserHolder.removeUser();
    }


}
