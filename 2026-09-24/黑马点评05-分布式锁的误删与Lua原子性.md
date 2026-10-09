今天做的是分布式锁最难啃的一段：**锁被误删**，以及用 **Lua 脚本**把它彻底修掉。

这一天的产出是脚本本身（`unlock.lua`）。把它封装成 `SimpleRedisLock` 类是 09-28 的事，但那天的代码里**保留了今天这两次迭代的痕迹**，所以两篇要对着看。

Lua、`KEYS`/`ARGV`、`EVALSHA` 这些知识点单开在 `2026-09-24-Redis.md`。

---

# 分布式锁的误删与 Lua 原子性

> **说明**：本文**只讲后端**，不涉及前端实现。
>
> 对照代码：`unlock.lua`、`SimpleRedisLock`（09-28 落地，但保留了今天的演进痕迹）。

## 一、功能定位

`SET NX` 只能保证「同一时刻只有一个人抢到锁」。但**锁用完之后要还**，而「还锁」这个动作里藏着两个坑：

| 坑 | 后果 | 本日的解决 |
| --- | --- | --- |
| **锁被别人删掉** | 两个人同时持锁 → 并发问题回归 | 给锁加「归属标识」 |
| **「判断归属 + 删除」不是原子的** | 判断完的瞬间锁易主，还是删错 | **Lua 脚本** |

这一天就是把这两个坑依次堵上。

## 二、第一版：没有标识的锁 —— 会误删

### 2.1 版本一的写法

```java
// 最朴素的实现
Boolean success = stringRedisTemplate.opsForValue()
        .setIfAbsent(key, "1", timeout, TimeUnit.SECONDS);
return Boolean.TRUE.equals(success);

// 释放
stringRedisTemplate.delete(key);
```

### 2.2 它会怎么出错

`setIfAbsent` 带了过期时间（TTL），这是为了**防止持锁线程挂掉导致死锁**。但 TTL 本身也带来了新问题：

```text
时刻 0s   线程1 抢到锁，锁 TTL = 10s
时刻 0~10s 线程1 执行业务……
           ↓ 业务卡住了，超过了 10 秒
时刻 10s  锁自动过期（Redis 删的，不是线程1删的）
时刻 11s  线程2 抢到锁并开始执行
时刻 12s  线程1 终于执行完，进入 finally → delete(key)
                    ↑ 它删掉的是【线程2 的锁】！
时刻 13s  线程3 抢到锁（因为锁又没了）
          → 线程2 和线程3 同时在持锁执行 → 互斥彻底失效
```

**根因**：线程1 释放锁时，**没有确认这把锁是不是自己加的**。

> 这个 bug 有个很隐蔽的特点：**平时不会出现**。只有当「业务执行时间 > 锁的 TTL」时才会触发。
> 开发时业务很快，测试环境压不出问题，一上生产（GC 停顿、慢 SQL、网络抖动）就偶发。

## 三、第二版：给锁加一个「归属标识」

### 3.1 思路

加锁时把「谁加的」写进 value，释放时先比一比：

```java
@Override
public boolean tryLock(Long timeout) {
    String key = KEY_PREFIX + name;
    String id = ID_PREFIX + Thread.currentThread().getId();
    Boolean success = stringRedisTemplate.opsForValue()
            .setIfAbsent(key, id, timeout, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}
```

### 3.2 标识怎么设计：`UUID + 线程id`

```java
private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
String id = ID_PREFIX + Thread.currentThread().getId();
```

代码里的注释把这个设计讲得很清楚：

> 每个 jvm 的 threadid 都是自增的，如果多线程操控同一个用户购买，有可能导致 threadid 相等
> 加上 UUID 是为了避免两个 jvm 的线程 id 对上导致误删锁
> **UUID 保证每个 JVM 唯一，getid 保证每个 JVM 内部的线程 id 唯一**

拆开看这两层：

| 部分 | 保证什么 | 为什么不够 |
| --- | --- | --- |
| `Thread.currentThread().getId()` | 同一 JVM 内唯一 | **不同 JVM 的线程 id 会重复**（都是 1、2、3…） |
| `UUID`（每 JVM 一个） | 跨 JVM 唯一 | 单用无法区分同 JVM 内的线程 |
| **两者拼接** | **全局唯一** | — |

> **一句话**：`UUID` 标识「哪台机器/哪个 JVM」，`threadId` 标识「那个 JVM 里的哪个线程」。
> 两层组合起来才唯一 —— 这是分布式标识的通用套路（就像订单号里嵌机器号）。
>
> `UUID.randomUUID().toString(true)` 的 `true` 表示**去掉横线**，32 位。而登录篇用的是
> `toString()`（带横线 36 位）—— 两处不一致，但都不影响功能。

### 3.3 第二版的释放逻辑（已被注释掉）

```java
//@Override
//public void releaseLock() {
//    String threadId = ID_PREFIX + Thread.currentThread().getId();
//    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//    //判断锁是否还是自己的
//    if (threadId.equals(id)) {
//        //是的就删除
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//    }
//    //不是就不删除
//}
```

这一版**功能上对了** —— 线程1 回来时会发现锁里的标识是线程2 的，于是不删。

### 3.4 但它还有一个更隐蔽的问题

代码里紧挨着这段注释的，是学生自己写下的分析：

> 如果线程1 在获取锁后阻塞时间超过 ttl，那么它有可能会在锁过期后（线程2 获取到锁时）进行删除，导致线程3 获取到锁，2、3 并发

这正是**判断和删除之间被插了一刀**：

```text
线程1: GET lock  →  "线程1的id"
        ↓ 判断通过，准备删除……但先被调度走了
                              ← 此刻锁 TTL 到了，Redis 自动删掉
                              ← 线程2 抢到锁，写入 "线程2的id"
线程1:                     DEL lock      ← 还是删掉了线程2 的锁！
```

**注意这和版本一的区别**：版本一是「无条件删」，版本二是「判断后再删」。但**判断和删除是两条独立的命令**，中间依然可以插入别的命令。

> 这和缓存篇里 `putAll` + `expire` 是同一类问题：
> **单条命令原子 ≠ 多条命令原子。**

## 四、第三版：用 Lua 把「判断 + 删除」合成一条命令

### 4.1 `unlock.lua`

```lua
local id = redis.call("get",KEYS[1])
if(ARGV[1] == id)
then
    return redis.call("del",KEYS[1])
end
    return 0
```

逐行看：

| 行 | 说明 |
| --- | --- |
| `local id = redis.call("get",KEYS[1])` | 取当前锁里存的标识；**key 从 `KEYS[1]` 拿，不是写死的** |
| `if(ARGV[1] == id)` | 和自己线程的标识比较；**自己的标识从 `ARGV[1]` 传进来** |
| `return redis.call("del",KEYS[1])` | 相等才删，返回 `DEL` 的结果（1 或 0） |
| `return 0` | 不相等就什么也不做，返回 0 |

**整个脚本在 Redis 里是一条命令**，中间不会插入任何其他客户端的操作 —— 所以「取 → 比 → 删」之间**不可能**再被别的线程插进去。

### 4.2 为什么 `KEYS` 和 `ARGV` 要分开传

- `KEYS[1]` = **要操作的 key**（`lock:order:1`）
- `ARGV[1]` = **纯参数**（线程标识）

这个划分不是随便定的：Redis 集群需要**提前知道脚本会碰哪些 key**，才能判断该发给哪个节点。
所以 key 必须放 `KEYS`、**不能拼在字符串里**。

> 更多细节（`EVAL`/`EVALSHA`、集群槽、Lua 限制）见 `2026-09-24-Redis.md`。

### 4.3 释放锁的最终形态

```java
@Override
public void releaseLock() {
    stringRedisTemplate.execute(UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX + name),
            ID_PREFIX + Thread.currentThread().getId());
}
```

- `Collections.singletonList(...)` —— 只有一个 key，比 `Arrays.asList()` 更轻
- 第三个参数是 `ARGV`，这里传的就是**本线程的标识**
- **不需要判断返回值**。因为不确定的只有「删没删」，而两种结果都是「正常」的（删了 = 我的锁我收走；没删 = 锁已经不是我的）

### 4.4 脚本的加载

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

static {
    UNLOCK_SCRIPT = new DefaultRedisScript<>();
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    UNLOCK_SCRIPT.setResultType(Long.class);
}
```

要点：

- **`static` 块里初始化**，脚本是全局共享的常量，只加载一次
- **`setResultType(Long.class)` 必须设**。不设的话 Redis 返回的整数会按默认类型反序列化，容易 `ClassCastException`
- 脚本放在 `src/main/resources/unlock.lua`，**和 Java 代码分开维护**

## 五、三次迭代的对照

| | 版本一 | 版本二 | **版本三（当前）** |
| --- | --- | --- | --- |
| 锁的 value | `"1"` | `UUID-threadId` | `UUID-threadId` |
| 释放方式 | 无条件 `DEL` | `GET` 后判断再 `DEL` | **Lua 脚本** |
| 会误删吗 | ✗ 会 | 极端情况下**仍会** | ✓ 不会 |
| 原因 | 不认锁的归属 | **判断和删除不原子** | 整段原子 |

## 六、易错点

1. **`setIfAbsent` 的返回值是包装类型 `Boolean`，可能为 `null`**（pipeline / 事务里必然为 null）。所以要写 `Boolean.TRUE.equals(success)`，不能 `return success` 或 `!success` —— 会 NPE。
2. **`ID_PREFIX` 必须是 `static final`**。如果写在方法里每次 `new` 一个 UUID，同一个线程两次调用拿到的标识就不同了，**自己的锁永远删不掉**。
3. **Lua 里 `KEYS[1]` 不要写成拼接的字符串**。集群模式下会直接报错，且服务端无法预判 key。
4. **`redis.call("get", ...)` 取不到时返回 `false`（Lua 视角）**，而不是 `nil` 或空串。所以「锁不存在」时 `ARGV[1] == id` 会是 `false`，正确地走向 `return 0`。
5. **锁的 TTL 仍然要设**，且仍然要**大于业务最长执行时间**。Lua 只解决了「删错」，没解决「业务跑太久锁提前过期」——那个要靠 Redisson 的看门狗（见 `2026-09-28/黑马点评06`）。
6. **释放锁必须放 `finally`**，否则业务抛异常时锁一直不释放，要等 TTL 到期。

## 七、不依赖前端的验证方法

误删问题**很难靠正常操作复现**，因为它要求「业务执行时间恰好跨过 TTL」。要验证得**主动制造这个交叉**：

```java
@Test
public void testLockOverlap() throws InterruptedException {
    // 1. 用一个极短的 TTL（比如 2 秒）把锁的生命周期压到可观测的范围
    SimpleRedisLock lock = new SimpleRedisLock("order:1", stringRedisTemplate);

    // 2. 线程1：抢锁，然后故意睡 3 秒（超过 TTL）
    new Thread(() -> {
        lock.tryLock(2L);
        try {
            Thread.sleep(3000);          // 睡到锁自动过期
        } catch (InterruptedException e) { }
        lock.releaseLock();              // ← 此时锁已经是别人的了
    }).start();

    Thread.sleep(2500);                  // 等锁过期

    // 3. 线程2：此刻抢到锁
    boolean got = lock.tryLock(10L);
    System.out.println("线程2 抢到锁 = " + got);      // 期望 true
    Thread.sleep(1500);

    // 4. 关键一步：看锁还在不在
    System.out.println("锁还在吗 = " + stringRedisTemplate.hasKey("lock:order:1"));
    // 期望：true  ← 线程1 的 releaseLock 没有删掉线程2 的锁

    lock.releaseLock();
}
```

**判读标准**：

| 输出 | 含义 |
| --- | --- |
| 锁还在 = `true` | ✓ Lua 生效，没误删 |
| 锁还在 = `false` | ✗ 被线程1 删掉了 → 回到版本二的问题 |

> 把 `releaseLock` 临时换成**注释掉的版本二**实现，再跑一次 —— 会看到两个不同的结果。
> 对照跑一遍，比看任何解释都直观。

## 八、可以更好的地方

### 8.1 锁的 TTL 靠「人肉估算」

**现状**：TTL 由调用方传入，业务执行时间超过它就会出现「两个线程同时持锁」。

**改法**：这就是 Redisson **看门狗（WatchDog）**要解决的问题 —— 加锁后起一个定时任务，**每 TTL/3 就自动续期一次**，只要进程还活着锁就不会过期。

见 `2026-09-28/黑马点评06` 的 Redisson 部分。

### 8.2 不可重入

**现状**：同一个线程第二次 `tryLock` 会失败（value 会被覆盖成同一个 id，但 `setIfAbsent` 仍然返回 false）。

**改法**：用 Hash 存 `{标识: 重入次数}`，每次加锁 `HINCRBY`，释放时递减到 0 才真正 `DEL`。这就是 Redisson 的**可重入锁**原理。

### 8.3 `releaseLock` 没有返回值

**现状**：调用方无法知道锁到底删没删。

**改法**：Lua 已经 `return` 了 `1/0`，把它透出来即可：

```java
public boolean releaseLock() {
    Long r = stringRedisTemplate.execute(UNLOCK_SCRIPT, keys, id);
    return Long.valueOf(1L).equals(r);      // 1 = 确实删掉了自己的锁
}
```

> 这样在排查线上问题时能明确区分「锁被自己正常释放」和「锁早已过期易主」。

### 8.4 优先级

| 改动 | 成本 | 收益 | 建议 |
| --- | --- | --- | --- |
| 加看门狗（或直接用 Redisson） | 高（换实现） | 高（根治 TTL 估算） | **建议做** |
| 支持可重入 | 中 | 中（取决于业务） | 看情况 |
| `releaseLock` 透出返回值 | 三行 | 低（排查方便） | 看情况 |
