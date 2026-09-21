package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
@Resource
    private StringRedisTemplate stringRedisTemplate;

    //order 越小优先级越高

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
       registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
               "/user/login",
               "/user/code",
               "/shop/**",
               "/shop-type/**",
               "/upload/**",
               "/blog/hot",
               "/voucher/**"
       ).order(1);


       registry.addInterceptor(new RefreshInterceptor(stringRedisTemplate)).order(0);
    }
}
