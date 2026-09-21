今天做的是把短信登录从 Session 换成 Redis + Token，以及配套的两个拦截器。

09-17 那篇记的是 Session 版（`黑马点评01-基于 Session 的短信登录.md`），这篇记换完之后的样子。
Session 版对应的知识点单开在 `2026-09-19-Redis.md` 里。

---

# 基于 Redis 实现短信登录

> **说明**：本文**只讲后端**，不涉及前端实现。
>
> 对照代码：`UserController`、`UserServiceImpl`、`RefreshInterceptor`、`LoginInterceptor`、`MvcConfig`、`RedisConstants`。

## 一、功能定位

手机号 + 短信验证码登录，免密码。功能上和 09-17 的 Session 版**完全一样**，换掉的是登录态存在哪：

- **之前**：登录态在 Tomcat 单机内存（Session），凭据是 Cookie 里的 `JSESSIONID`
- **现在**：登录态在一份共享的 Redis 里，凭据是服务端生成的随机 `token`

变化的是**存储位置和凭据形式**，不变的是业务规则（首登即注册、验证码 2 分钟有效）。

## 二、接口契约

> 契约四要素：**URL + 方法、参数放哪、返回什么、凭据怎么带**。
> 和 Session 版逐条对比：URL、方法、参数位置都没变，**变的只有后两条** ——
> 而它们其实是同一件事的两面：凭据从响应头搬到了响应体，所以「返回什么」里多了一个 `data`。

### 接口 1：发送验证码

```text
POST /user/code?phone=13456789001
参数位置：query 参数 phone                        → @RequestParam("phone") String phone
返回：200 {"success":true}
     200 {"success":false,"errorMsg":"手机号格式错误!"}
```

### 接口 2：登录（含隐式注册）

```text
POST /user/login
Content-Type: application/json
参数位置：JSON body {"phone":"...","code":"..."}   → @RequestBody LoginFormDTO
返回：200 {"success":true,"data":"a1b2c3d4-e5f6-..."}   ← token 在响应体里
     200 {"success":false,"errorMsg":"验证码错误!"}
副作用：响应头仍会有 Set-Cookie: JSESSIONID=xxxx     ← 见下方说明
```

> **和 Session 版最大的接口差异：凭据从响应头搬到了响应体。**
> Session 版靠 `Set-Cookie: JSESSIONID=...` 下发凭据，前端一行代码不用写；
> Redis 版**把 token 当普通业务数据放在 `data` 里返回**，前端拿到后自己存起来（localStorage），后续请求自己加到头里。
>
> **但 `Set-Cookie` 并没有消失。** 因为方法签名上还留着 `HttpSession session` 参数，Spring MVC 会为它调一次 `request.getSession()` —— **这会真的建出一个 Tomcat session 并下发 JSESSIONID**。
> 只是这个 cookie 已经没有任何业务含义了（真正的凭据是 token），属于纯粹的遗留负担。见「易错点 9」。

### 接口 3：获取当前用户（受保护，用于验证拦截器）

```text
GET /user/me
凭据位置：请求头 authorization: a1b2c3d4-e5f6-...
返回：200 {"success":true,"data":{"id":5,"nickName":"user_abc123","icon":""}}
     401（拦截器直接返回，无响应体）
```

> **401 依然是这个项目里唯一用 HTTP 状态码表达业务含义的地方**，因为拦截器绕过了 Controller，没有 `Result` 可用。

## 三、后端分层调用链

```text
HTTP 请求
   ↓
DispatcherServlet          路由匹配 + 参数绑定
   ↓
RefreshInterceptor         order(0)，拦截【所有】路径
   ├─ 取 authorization 头 → 查 Redis → 填 UserHolder(ThreadLocal)
   └─ 刷新 token TTL（滑动续期）
   ↓
LoginInterceptor           order(1)，只拦需要登录的路径
   └─ 只看 UserHolder 里有没有人 → 没有就 401
   ↓
UserController             只做「收参数 → 调 Service → 返回 Result」
   ↓
IUserService (接口)
   ↓
UserServiceImpl            业务逻辑全在这里
   ├─ UserMapper         → MySQL: tb_user
   └─ StringRedisTemplate → Redis（替代了原来的 HttpSession）
```

**分层铁律**（和 09-17 一致）

- Controller 不写业务
- Service 不碰 `HttpServletRequest`（所以要用 `UserHolder` 这个 ThreadLocal 传递当前用户）
- Mapper 不写逻辑

> 唯一的变化：`UserServiceImpl` 的依赖从 `HttpSession` 变成了 `StringRedisTemplate`。
> 但注意 `sendCode` / `login` 的签名上**还留着 `HttpSession session` 参数**，方法体里已经完全不使用 —— 这是那段代码的化石，见「易错点 9」。

## 四、关键业务逻辑

### 1. 发送验证码

```java
@Override
public Result sendCode(String phone, HttpSession session) {
    // 检验手机号是否符合格式
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误!");
    }
    // 符合，创建验证码
    String code = RandomUtil.randomNumbers(6);

    // 保存到 redis，设置有效期
    stringRedisTemplate.opsForValue()
            .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

    log.debug("已成功发送验证码 {}", code);

    return Result.ok();
}
```

要点：

- 和 Session 版的**唯一区别是把 `session.setAttribute("code", code)` 换成了 Redis 的 `set`**
- `LOGIN_CODE_KEY + phone` → 实际 key 是 `login:code:13456789001`
- `LOGIN_CODE_TTL` 单位是**分钟**，值 `2` → 验证码 2 分钟有效
- **key 里必须带手机号**。Session 方案下每个客户端一个独立 session，key 写 `"code"` 就够；Redis 是所有用户共用一份，不带手机号会互相覆盖

### 2. 登录 + 隐式注册

```java
@Override
public Result login(LoginFormDTO loginForm, HttpSession session) {
    String phone = loginForm.getPhone();
    String code = loginForm.getCode();

    // (1) 判断手机号
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误!");
    }

    // (2) 判断验证码 —— 从 Redis 取
    String cachedcode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
    log.debug("redis:{},请求:{}", cachedcode, code);

    if (cachedcode == null || !cachedcode.equals(code)) {
        return Result.fail("验证码错误!");
    }

    // (3) 查询用户，不存在就注册
    User user = query().eq("phone", phone).one();
    if (user == null) {
        user = creatUser(phone);
    }

    UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

    // (4) 信息保存到 redis，前缀 + uuid 实现 key 的唯一性
    String token = UUID.randomUUID().toString();

    Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
            CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

    stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
    stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);

    // 返回 token，下次请求根据 token 查询信息
    return Result.ok(token);
}
```

要点：

- **(2) 的判空顺序不能反**：必须 `cachedcode == null ||` 放在前面。如果写成 `!cachedcode.equals(code)`，Redis 里查不到时会直接 **NPE**
- **验证码比对用 `.equals` 不用 `==`**。这里两边都是 String，且 `code` 来自 JSON 反序列化 —— 都是新对象，`==` 一定为 false
- **(3) 注册依然是隐式的**：查不到就建号，`creatUser` 内部 `save(user)` 走 MyBatis-Plus 的 INSERT，`@TableId(type=IdType.AUTO)` 会回填自增主键
- **存进 Redis 的是 `UserDTO` 不是 `User` 实体**。`UserDTO` 只有 `id / nickName / icon`，把 `password`、`phone` 挡在登录态之外
- **(4) 用的是 Hash 不是 String**：`putAll` 批量写入各字段，读的时候 `HGETALL` 一次取回。好处是以后要单独改某个字段（比如改昵称）不用全量覆盖
- **`token` 用 `UUID.randomUUID()` 生成**，不暴露手机号，避免敏感信息在请求头 / 日志里明文出现
- **写入和设过期是两次独立的 Redis 调用**（`putAll` + `expire`）。中间挂掉会留下一个永不过期的 token —— 见「易错点 6」

> `CopyOptions` 那两个设置是**必须的**，不是可选优化，原因在知识点笔记里：
> Hash 的 field 和 value 都必须是 String，而 `id` 是 `Long`；`icon` 可能是 `null`。

### 3. 双拦截器

这是 09-17 到 09-19 最值得记的一处结构变化：**拦截器从一个拆成了两个，职责不同**。

讲拦截器之前先补上 `UserHolder` —— 两个拦截器全靠它交接，但 09-17 那篇没展开它：

```java
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user) { tl.set(user); }
    public static UserDTO getUser()            { return tl.get(); }
    public static void removeUser()            { tl.remove(); }
}
```

它就是个 `ThreadLocal<UserDTO>` 的静态包装。存在的意义：**让 Controller / Service 在任何地方都能拿到「当前用户」，而不用把 `HttpServletRequest` 往 Service 层传。**

> 生命周期严格等于「一次请求」：`RefreshInterceptor` 进来时 `saveUser`，`afterCompletion` 走时 `removeUser`。
> 两个拦截器的分工，本质就是在**这次交接的前后各插了一刀**。

#### RefreshInterceptor —— 管「认人 + 续期」，拦所有路径

```java
public class RefreshInterceptor implements HandlerInterceptor {

    // 自己定义的类无法实现自动注入（非 spring 类构建）
    private StringRedisTemplate stringRedisTemplate;

    public RefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 获取 token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;                     // ← 没 token 也放行，不在这里拦
        }

        // 获取用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);

        // 匹配用户
        if (entries.isEmpty()) {
            return true;                     // ← 查不到也放行
        }

        // 符合则放行，保存到 ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        // 有请求，说明用户在操作，刷新有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserHolder.removeUser();             // 必须清理
    }
}
```

**它的 `preHandle` 永远 `return true`** —— 这是关键设计。它不做鉴权决策，只做两件事：**能认出人就填 ThreadLocal，认不出就什么都不做**。

另外两点：

- **构造函数注入 `stringRedisTemplate`**，不用 `@Resource`。因为这个类是 `new` 出来的、不归 Spring 管，注解不会生效。谁 `new` 它谁负责把依赖传进来（`MvcConfig` 里就是这么做的）
- **`afterCompletion` 里清理 ThreadLocal**。因为它是拦截所有路径的、且 `preHandle` 必返回 true，所以**这个清理动作一定会执行**，不会漏

#### LoginInterceptor —— 管「拦不拦」，只判断 ThreadLocal

```java
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 从 ThreadLocal 取出用户，判断该用户是否存在
        if (UserHolder.getUser() == null) {
            // 不存在拦截
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
```

**它完全不碰 Redis、不碰 token**，只看 `UserHolder` 里有没有人。因为「认人」这一步已经被 `RefreshInterceptor` 做完了。

#### 注册顺序

```java
// order 越小优先级越高
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
```

两个关键点：

1. **`order(0)` 的 RefreshInterceptor 先执行**，`order(1)` 的 LoginInterceptor 后执行。顺序反了的话，LoginInterceptor 查 ThreadLocal 永远为空，**所有接口全部 401**
2. **RefreshInterceptor 不配 `excludePathPatterns`** —— 它拦截**全部**路径。这正是 09-17 遗留问题的解法，见下一节

### 4. 为什么必须拆成两个拦截器

这是 course 1.9 要解决的问题，也是这次改造的核心动机。

**问题**：Session 版的拦截器只拦「需要登录的路径」。假设用户一直在刷首页 `/shop/**` —— 这是个**不需要登录的路径**，被排除在拦截器之外，那么：

- 拦截器根本不执行 → **token 的 TTL 不会被刷新**
- 用户明明在活跃地使用系统，**却被判定为「长时间未操作」而掉线**

反过来，如果让拦截器把所有路径都拦下来刷新，那它就得同时负责鉴权 —— 但 `/shop/**` 这些路径是允许匿名访问的，**拦下来也不该 401**。

**解法**：拆成两个，各管一件事。

| | RefreshInterceptor | LoginInterceptor |
| --- | --- | --- |
| 职责 | **认人 + 续期** | **鉴权** |
| 拦截范围 | **全部路径**（无 exclude） | 只拦需要登录的 |
| 判断依据 | 查 Redis | 只看 `UserHolder` |
| 失败行为 | **放行**（`return true`） | `setStatus(401)` + `return false` |
| 成功行为 | 填 ThreadLocal + 刷新 TTL | 放行 |
| 执行顺序 | `order(0)` 先 | `order(1)` 后 |

> **一句话本质**
>
> 「识别用户」和「拦截用户」是**两件独立的事**，只是过去恰好写在一个拦截器里。
> 拆开的判据是：**作用范围不同** —— 认人要在所有请求上做（为了续期），鉴权只在部分请求上做。
> 硬塞在一个类里，就必然要在「续期生效范围」和「鉴权生效范围」之间二选一。

> **⚠️ 一个前提条件（很容易被忽略）**
>
> 这套拆分要真正生效，**前端必须把 `authorization` 头加到所有请求上**，包括 `/shop/**` 这些公开路径。
> 如果前端只在调登录相关接口时才带 token，那 `RefreshInterceptor` 每次都在 `StrUtil.isBlank(token)` 处直接 return，
> **续期效果等于零，拆分也就白拆了**。

### 5. 数据落点

| 数据 | 存哪 | key | 生命周期 | 谁清理 |
| --- | --- | --- | --- | --- |
| 验证码 | Redis String | `login:code:<手机号>` | 固定 2 分钟 | Redis 自动 |
| 登录用户 | Redis Hash | `login:token:<uuid>` | 滑动 10 小时 | Redis 自动 |
| 当前请求的用户 | JVM ThreadLocal | — | 单次请求 | `RefreshInterceptor.afterCompletion` |
| 用户档案 | MySQL `tb_user` | — | 永久 | — |
| 浏览器侧凭据 | localStorage | `token` | 随前端 | **前端自己** |
| ~~遗留的 Tomcat session~~ | Tomcat 内存 | `JSESSIONID` | 30 分钟不活动 | Tomcat 自动（**已无人使用**） |

**关键结论**：对比 09-17 那张表，有**两处从「自动」变成了「手动」**：

1. **凭据的下发和回带**：Session 版是 Tomcat 自动 `Set-Cookie` + 浏览器自动回带；Redis 版是**服务端手动放进响应体、前端手动存、手动加请求头**。
2. **过期时间**：Session 版只有「30 分钟不活动」这固定一档；Redis 版**每个 key 自己设**（验证码 2 分钟、token 10 小时），而且能滑动续期。

## 五、易错点

### Token / Redis 相关

1. **拦截器 `return false` 前必须 `response.setStatus(401)`**。只 `return false` 的话响应是 **HTTP 200 空体**，curl 和前端都判断不出「未登录」。
2. **`RefreshInterceptor` 绝对不能配 `excludePathPatterns`**。它一旦被排除，被排除的路径就**不会刷新 TTL** —— 用户在首页一直活跃，却被判定「未操作」而掉线。
   > 注意这里**不存在** ThreadLocal 残留问题：被排除的请求 `preHandle` 压根不执行，也就没往 ThreadLocal 里放过东西；而只要注册在所有路径上，`afterCompletion` 就一定执行、一定清理。残留只会发生在「存了但没清」的情况下。
3. **两个拦截器的 `order` 不能反**。Refresh 必须**小于** Login。反了就是全站 401。
4. **`authorization` 头名前后端必须一致**。拼错了不报错，只是 `StrUtil.isBlank(token)` 恒为 true → 永远认不出人 → 受保护接口全 401。这类问题最难查，因为它「不报错」。
5. **验证码判空顺序不能反**：`if (cachedcode == null || !cachedcode.equals(code))`。`null` 检查必须在前面，否则 NPE。
6. **`putAll` + `expire` 不是原子的**。两次独立往返，中间挂掉会留下**永不过期的 token**。
7. **Hash 的 field/value 必须都是 String**。`BeanUtil.beanToMap` 会产生 `Long` 类型的 `id`，不处理 `putAll` 会抛 `ClassCastException`。`setFieldValueEditor(...toString())` 就是为它存在的，**不是可选优化**；另一个 `setIgnoreNullValue(true)` 则是防御性的（当前 `UserDTO` 三个字段都不为 null）。
8. **`StringRedisTemplate` 下 `entries` 返回空 Map 而不是 `null`**，判空只能用 `isEmpty()`。

### 遗留 / 未完成

> 这一段列的是**现状**；具体「怎么改」连同代码写在知识点篇的
> **「八、可以更好的地方」**（按安全 / 正确性 / 性能 / 完整性分了优先级）。

9. **`HttpSession session` 参数还留在 `sendCode` / `login` 的签名上**（`UserController` → `IUserService` → `UserServiceImpl` 一路上都带着），方法体里已经完全没用。
   **但它不是「无害的化石」**：Spring MVC 见到 `HttpSession` 参数会调 `request.getSession()`，于是**每个登录 / 发码请求都会凭空建出一个 Tomcat session**，还顺带下发 `JSESSIONID` cookie。这个「已经死了」的参数仍在占服务端内存、发无用的 cookie。
10. **验证码校验通过后没有删除**。`login()` 里 `get` 之后没有 `delete`，2 分钟内同一个验证码可重复使用（重放）。
11. **验证码既没有发送频率限制，也没有错误次数限制**。既不防刷，也能被爆破。
12. **验证码被明文写进了日志**。`application.yaml` 里开着 `logging.level.com.hmdp: debug`，而 `log.debug("已成功发送验证码 {}", code)` 会把它原样打出来 —— 能看到日志就等于能绕过短信验证。
13. **`/user/logout` 未实现**，目前直接 `return Result.fail("功能未完成")`。
14. **同一用户反复登录会产生多个并存的有效 token**，旧的不会被回收，也踢不掉。

### 通用（跨版本）

15. **`Result` 的 `success` 和 HTTP 状态码是两套体系**。业务失败是 200 + `success:false`；只有拦截器用 401。
16. **`WebExceptionAdvice` 捕获异常后返回的也是 200**，所以「接口返回 200」不等于功能正常，必须看响应体的 `success`。
17. **Service 层拿不到 `HttpServletRequest`**，需要「当前用户」时必须走 `UserHolder`。
