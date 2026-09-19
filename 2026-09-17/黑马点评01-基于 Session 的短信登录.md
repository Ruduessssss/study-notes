完成了黑马点评的前置条件,导文件,前端等.

第一部分做的是短信登录

![1653066005825](C:\Users\admin\Desktop\study-notes\2026-09-17\黑马点评01.assets\1653066005825.png)

今天只做了基于Session实现登录的发送短信验证码和依据短信验证码实现登录,注册

总结项目内容的重点业务逻辑

---

# 基于 Session 实现短信登录

> **说明**：这部分代码在项目里已被 Redis + Token 版替换。本文按当时实现整理，对照 `UserController.sendCode(..., HttpSession session)`、`IUserService.login(..., HttpSession session)` 签名上遗留的 `session` 参数可以看出痕迹 —— 这些参数现在已完全没被使用，是那段代码的化石。
>
> 本文**只讲后端**，不涉及前端实现。

## 一、功能定位

手机号 + 短信验证码登录，免密码。

**首次登录时手机号不存在则自动注册** —— 「注册」和「登录」是同一个接口 `/user/login` 的两个分支，项目里没有独立的注册接口。

登录成功后，服务端在内存中维护一份登录态，客户端靠 Cookie 自动携带凭据。

## 二、接口契约

> 契约四要素：**URL + 方法、参数放哪、返回什么、凭据怎么带**。这是后端定义、前端遵守的东西。
> 只要定清楚这四条，前端就不再是黑盒。

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
返回：200 {"success":true}
     200 {"success":false,"errorMsg":"验证码错误!"}
副作用：响应头 Set-Cookie: JSESSIONID=xxxx         ← 服务端创建 session 后自动下发
```

### 接口 3：获取当前用户（受保护，用于验证拦截器）

```text
GET /user/me
凭据位置：请求头 Cookie: JSESSIONID=xxxx
返回：200 {"success":true,"data":{"id":5,"nickName":"user_abc123","icon":""}}
     401（拦截器直接返回，无响应体）
```

> **401 是这个项目里唯一用 HTTP 状态码表达业务含义的地方**，因为拦截器绕过了 Controller，没有 `Result` 可用。

## 三、后端分层调用链

```text
HTTP 请求
   ↓
DispatcherServlet        路由匹配 + 参数绑定
   ↓
拦截器链                 进入业务代码前做登录态校验
   ↓
UserController           只做「收参数 → 调 Service → 返回 Result」
   ↓
IUserService (接口)
   ↓
UserServiceImpl          业务逻辑全在这里
   ├─ UserMapper         → MySQL: tb_user
   └─ HttpSession        → Tomcat 内存（后改为 StringRedisTemplate → Redis）
```

**分层铁律**

- Controller 不写业务
- Service 不碰 `HttpServletRequest`（所以要用 `UserHolder` 这个 ThreadLocal 传递当前用户）
- Mapper 不写逻辑（`UserMapper` 是空接口，只继承 `BaseMapper<User>`，SQL 由 MyBatis-Plus 生成）

## 四、关键业务逻辑

### 1. 发送验证码

```java
@Override
public Result sendCode(String phone, HttpSession session) {
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误!");
    }
    String code = RandomUtil.randomNumbers(6);
    session.setAttribute("code", code);
    log.debug("发送短信验证码成功，验证码：{}", code);   // 真实项目此处发短信
    return Result.ok();
}
```

要点：

- `HttpSession` 是**方法参数**，Spring MVC 自动注入当前请求的 session，不需要自己 `request.getSession()`
- 手机号校验必须在生成验证码**之前**，否则会对着非法手机号发码
- **验证码存在 session 里，所以 key 只用 `"code"` 就够了** —— 每个客户端一个独立 session，天然隔离。这点和 Redis 版形成鲜明对比（见第七节）

### 2. 登录 + 隐式注册

```java
@Override
public Result login(LoginFormDTO loginForm, HttpSession session) {
    String phone = loginForm.getPhone();

    // (1) 校验手机号
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误!");
    }

    // (2) 校验验证码
    Object cacheCode = session.getAttribute("code");     // 取出来是 Object
    if (cacheCode == null || !cacheCode.toString().equals(loginForm.getCode())) {
        return Result.fail("验证码错误!");
    }

    // (3) 查用户，查不到就注册
    User user = query().eq("phone", phone).one();        // SELECT * FROM tb_user WHERE phone=?
    if (user == null) {
        user = createUserWithPhone(phone);
    }

    // (4) 存登录态（存 DTO，不存实体）
    session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

    return Result.ok();
}

private User createUserWithPhone(String phone) {
    User user = new User();
    user.setPhone(phone);
    user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
    save(user);          // INSERT，因 @TableId(type=IdType.AUTO) 会回填自增主键
    return user;
}
```

要点：

- `query()` / `save()` 都来自 `ServiceImpl<UserMapper, User>`，表名由 `User` 上的 `@TableName("tb_user")` 决定
- **注册是隐式的**：查不到就建号，这一步就是「注册」
- **存进 session 的是 `UserDTO` 不是 `User` 实体**。`UserDTO` 只有 `id / nickName / icon`，把 `password`、`phone` 挡在登录态之外 —— 敏感字段不该留在服务端会话里，更不该随响应返回
- `BeanUtil.copyProperties` 是对象属性拷贝，按名字匹配字段

### 3. 拦截器鉴权

```java
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if (user == null) {
            response.setStatus(401);
            return false;
        }
        UserHolder.saveUser((UserDTO) user);   // 存入 ThreadLocal，供后续各层使用
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserHolder.removeUser();               // 必须清理
    }
}
```

要点：

- **拦截器只负责「解析凭据、判定登录态、放进 ThreadLocal」**，业务代码不再关心登录
- `UserHolder` 用 `ThreadLocal<UserDTO>` 实现。好处：Controller / Service 任何地方都能直接 `UserHolder.getUser()`，不用每个方法都去解析 session，也不用把 `HttpServletRequest` 往 Service 层传
- `afterCompletion` 必须 `removeUser()`。Tomcat 线程池复用线程，不清会串号 —— 下一个请求会拿到上一个用户的身份，症状是「偶尔拿到别人的信息」，极难排查
- **排除路径必须包含 `/user/code` 和 `/user/login`**，否则死锁：登录页没登录 → 验证码接口被拦 → 401 → 永远登不上

### 4. 数据落点

| 数据 | 存哪 | key | 生命周期 | 谁清理 |
| --- | --- | --- | --- | --- |
| 验证码 | Tomcat 内存 Session | `code` | 30 分钟不活动过期 | Tomcat 自动 |
| 登录用户 | 同上 | `user` | 同上 | Tomcat 自动 |
| 用户档案 | MySQL `tb_user` | — | 永久 | — |
| 浏览器侧凭据 | Cookie | `JSESSIONID` | 随会话 | Tomcat 自动 `Set-Cookie` |

**关键结论**：session 方案下，**客户端一行代码都不用写** —— `JSESSIONID` 由 Tomcat 自动下发、浏览器自动回带。

## 五、易错点

### Session 相关

1. **拦截器 `return false` 前必须 `response.setStatus(401)`**。只 `return false` 的话响应是 **HTTP 200 空体**，curl 和前端都判断不出「未登录」。
2. **`/user/code`、`/user/login` 必须排除在拦截器之外**，否则死锁。
3. **多个拦截器时，`excludePathPatterns` 只对挂它的那个拦截器生效**。新加拦截器忘配排除，会把它一并拦下 —— 这是「拆分拦截器」时最经典的翻车方式。
4. **`session.setAttribute` / `getAttribute` 的 key 必须严格一致**。拼错了不报错、只是永远取到 null，最难查。
5. **`getAttribute` 返回 `Object`，必须强转**，且强转类型要与存入时一致，否则 `ClassCastException`。
6. **`afterCompletion` 里必须 `UserHolder.removeUser()`**，否则线程复用串号。
7. **`request.getSession()` 在没有 session 时会新建一个**，匿名请求也会在服务端堆 session 占内存。只读场景应考虑 `getSession(false)`（返回 null 而非新建）。
8. **验证码没有独立过期时间**。session 的 30 分钟不等于验证码该活 30 分钟。
9. **验证码用完没删**，理论上可重放（应 `session.removeAttribute("code")`）。

### 通用（跨版本）

10. **`Result` 的 `success` 和 HTTP 状态码是两套体系**。业务失败是 200 + `success:false`；只有拦截器用 401。
11. **`WebExceptionAdvice` 捕获异常后返回的也是 200**，所以「接口返回 200」不等于功能正常，必须看响应体的 `success`。
12. **Service 层拿不到 `HttpServletRequest`**，需要「当前用户」时必须走 `UserHolder` —— 这也是为什么要有这个工具类。

## 六、不依赖前端的验证方法

直连 8081 绕过 nginx，用 curl 把整条链路走通：

```bash
# 1) 发送验证码（验证码从 IDEA 控制台日志里看）
curl -i -X POST "http://localhost:8081/user/code?phone=13456789001"
# 期望：HTTP/1.1 200  {"success":true}

# 2) 登录，-c 把响应里的 Set-Cookie: JSESSIONID 存到文件
curl -i -c cookie.txt -X POST "http://localhost:8081/user/login" \
     -H "Content-Type: application/json" \
     -d '{"phone":"13456789001","code":"482913"}'
# 期望：HTTP/1.1 200  {"success":true}  + 响应头有 Set-Cookie: JSESSIONID=...

# 3) 带 session 访问受保护接口，-b 回带 cookie
curl -i -b cookie.txt "http://localhost:8081/user/me"
# 期望：200 {"success":true,"data":{...}}   ← 拦截器认出了你

# 4) 不带 cookie 访问同一个接口
curl -i "http://localhost:8081/user/me"
# 期望：401   ← 拦截器在工作；若是「200 空体」说明漏了 setStatus(401)
```

> 第 4 条是关键：它同时验证「拦截器生效」和「状态码没漏设」。

## 七、为什么后来必须换成 Redis

Session 方案的致命问题，以及几种补救方案的取舍：

| 方案 | 做法 | 问题 |
| --- | --- | --- |
| Session 复制 | Tomcat 之间互相同步 session | 节点越多同步风暴越严重，浪费内存 |
| 粘性会话 | nginx `ip_hash`，同 IP 固定打一台 | 负载不均；那台挂了该批用户全掉线 |
| （原始问题） | 多实例负载均衡下，A 机存的 session B 机不认 | **用户随机掉线** |
| **Session 共享** | **把登录态抽到 Redis，多实例共用** | **最优解** |

切换后的连锁变化（这是理解整段演进的关键）：

| 对比项 | Session 方案 | Redis + Token 方案 |
| --- | --- | --- |
| 登录态存哪 | Tomcat 单机内存 | Redis（多实例共享） |
| 验证码 key | `code`（session 天然隔离） | `login:code:<手机号>`（共用一份 Redis，必须靠手机号区分） |
| 登录态 key | `user` | `login:token:<uuid>`（Hash 结构） |
| 过期时间 | 固定 30 分钟 | 可精确设置（验证码 2 分钟）+ 滑动续期 |
| 客户端凭据 | Cookie `JSESSIONID`，**浏览器自动带** | 请求头 `authorization: <token>`，**客户端自己带** |
| 前端要不要写代码 | 不用 | 要（存 token + 加请求头） |

> **一句话本质**
>
> 登录态是一份「有有效期的、服务端持有的数据」，客户端只拿一把钥匙。
> Session 把数据和钥匙编号都放在单机内存 + Cookie 里；Redis 方案把数据放到共享存储，
> 钥匙（token）就得由客户端自己带着走 —— **这才是前端多写那几行代码的根本原因，不是前端技巧问题。**
