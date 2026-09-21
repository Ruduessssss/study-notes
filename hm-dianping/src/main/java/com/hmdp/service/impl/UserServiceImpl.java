package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //检验手机号是否符合格式
        if(RegexUtils.isPhoneInvalid(phone))
        {
            //不符合,返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //符合,创建验证码
        String code = RandomUtil.randomNumbers(6);

        //保存到redis,设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);

        log.debug("已成功发送验证码 {}",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        String code = loginForm.getCode();

        //判断手机号
        if(RegexUtils.isPhoneInvalid(phone))
        {
            //不符合,返回错误信息
            return Result.fail("手机号格式错误!");
        }

        //判断验证码

        String cachedcode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        log.debug("redis:{},请求:{}",cachedcode,code);

        if (cachedcode==null||!cachedcode.equals(code)) {
            return Result.fail("验证码错误!");
        }

        //查询用户
        User user = query().eq("phone", phone).one();

        if(user==null)
        {
            //user 不存在

            user = creatUser(phone);

        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        //信息保存到redis,前缀+uuid实现key的唯一性

        String token = UUID.randomUUID().toString();

        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,map);

        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.SECONDS);

        //返回token,下次请求根据token查询信息
        return Result.ok(token);
    }

    private User creatUser(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(6));

        save(user);

        return user;
    }
}
