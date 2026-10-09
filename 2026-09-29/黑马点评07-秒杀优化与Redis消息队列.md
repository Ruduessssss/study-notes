今天做的是**秒杀优化**：把「判断有没有购买资格」搬进 Redis，用 Lua 一次做完，再让一条后台线程异步落库。
中间换过几套「消息队列」（阻塞队列 → List → PubSub → Stream），最后落地的活代码是 **Stream 消费者组** 版本。

概念性的东西（List / PubSub / Stream 各自怎么回事）写在 `2026-09-29-Redis.md`，这篇只讲代码。

**注意**：项目里堆了历史版本，其中「基于阻塞队列」（6.3）和「同步下单 + Redisson 锁」两套**全是注释掉的**，别当成已实现。

---

# 秒杀优化与 Redis 消息队列

> **说明**：本文**只讲后端**，不涉及前端实现。
>
> 对照代码：`VoucherOrderController`、`IVoucherOrderService`、`VoucherOrderServiceImpl`、
> `seckillOrder.lua`、`VoucherServiceImpl`、`RedisIdWorker`、`RedisConstants`、`RedissonConfig`、`HmDianPingApplication`。

## 一、功能定位

**为什么需要优化：原来的秒杀要查库 + 加锁，QPS 上不去。**

原来的下单流程（就是 `VoucherOrderServiceImpl.java:242-286` 那段注释）一条线程串行走完：
查优惠券(库) → 判断秒杀时间 → 判断库存 → 校验一人一单(库) → 扣减库存(库) → 创建订单(库)。

**六步里四步要跟 MySQL 打交道，还全程持锁串行**，人一多锁竞争 + DB IO 就把响应时间顶上去。

思路是「把快的和慢的拆开」：**库存判断、一人一单判断**只「读 + 判断」，非常快 → 放进 **Redis + Lua**（原子）；
**扣库存、建订单**要落库、慢 → 交给**后台线程异步做**。

只要资格判断通过，这单**一定**能成 —— 于是可以**立刻返回 orderId**，让用户拿 id 去轮询；后台线程慢慢消费、落库。

> 这里**没有手动开线程池去并行跑那六步** —— 课程强调过，那种异步编排法在高并发下线程池瞬间被打满，而时效性不是这业务的诉求。
> 流水线：`请求线程 → Lua(判库存+判一人一单+扣减+记账+XADD) → 立刻返回 orderId`；`后台线程 → XREADGROUP → 建单落库 → XACK`。

## 二、接口契约

### 接口 1：秒杀下单（用户侧）

```text
POST /voucher-order/seckill/{id}
参数位置：路径变量 id                            → @PathVariable("id") Long voucherId
返回：200 {"success":true,"data":1234567890...}   ← data 就是订单 id
     200 {"success":false,"errorMsg":"库存不足"}
     200 {"success":false,"errorMsg":"用户重复下单"}
```

> ⚠️ **订单 id 是 18 位大整数**（`RedisIdWorker.nextId` 生成，量级 ≈ 1.04×10^17）。JS 的 `Number` 安全整数上限 `MAX_SAFE_INTEGER` 只有 `2^53-1 ≈ 9×10^15`（16 位），`JSON.parse` 会丢精度 —— 见易错点 6。

### 接口 2：新增秒杀券（管理侧，把库存灌进 Redis）

```text
POST /voucher/seckill → @RequestBody Voucher voucher ；返回 200 {"success":true,"data":<voucherId>}
副作用：写 tb_voucher + tb_seckill_voucher，并把库存写入 Redis 的 seckill:stock:{voucherId}
```

> `VoucherServiceImpl.java:44-58`。它是整条流水线的**前置条件** —— Lua 里的库存 key 全靠这一步写进去。

## 三、后端分层调用链

```text
【写入路径：建券】
POST /voucher/seckill → VoucherController.addSeckillVoucher
VoucherServiceImpl.addSeckillVoucher()  @Transactional
   ├─ save(voucher) → tb_voucher ；seckillVoucherService.save(...) → tb_seckill_voucher ；并把库存 SET 进 Redis 的 seckill:stock:{id}

【秒杀路径：抢券】
POST /voucher-order/seckill/{id} → VoucherOrderController.seckillVoucher
VoucherOrderServiceImpl.seckillVoucher(voucherId)      ← 请求线程
   ├─ redisIdWorker.nextId("order")                     → 先造好 orderId
   ├─ AopContext.currentProxy()                          → 存进字段 proxy
   ├─ stringRedisTemplate.execute(SECKILL_ORDER_SCRIPT)  → 跑 seckillOrder.lua（原子判断 + XADD）
   └─ return Result.ok(orderId)                          ← 立刻返回，不落库

【消费路径：后台线程，@PostConstruct 起】
VoucherOrderServiceImpl.init()  @PostConstruct
   └─ SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler())
        └─ VoucherOrderHandler.run()  （死循环）
             ├─ XREADGROUP stream.orders g1 c1 COUNT 1 BLOCK 2000 >   ← 取新消息
             ├─ HandleVoucherOrder(order)                             ← 抢 Redisson 锁
             │    └─ proxy.creatVoucherOrder(order)  @Transactional   ← 落库
             └─ XACK stream.orders g1 {id}                            ← 处理完才确认
```

**分层上最值得注意的一点**：`init()`（`VoucherOrderServiceImpl.java:77-82`）在 **Bean 初始化完的那一刻**
就把消费者线程提交出去 —— 它是「后台线程」和「Spring 容器」的接点：

```java
private ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();

@PostConstruct
private void init()
{
    SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
}
```

`@PostConstruct` 保证「容器一就绪就开始消费」，不需要 HTTP 请求触发；提交的 `VoucherOrderHandler`
内部是 `while(true)`、**永不返回**；它是**内部类**，所以能直接读外层的 `stringRedisTemplate` / `proxy` / `redissonClient`。

## 四、关键代码

### 4.1 `seckillOrder.lua` 逐行

```lua
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = "seckill:stock:"..voucherId

local orderKey = "seckill:order:"..voucherId

if (tonumber(redis.call("get", stockKey))<=0) then
    ---库存小于0
    return 1
end

if(redis.call("sismember",orderKey,userId)==1) then
    ---用户已经下过单
    return 2
end

redis.call("incrby",stockKey,-1)
redis.call("sadd",orderKey,userId)
redis.call("xadd","stream.orders","*","voucherId",voucherId,"userId",userId,"id",orderId)
return 0
```

| 行 | 说明 |
| --- | --- |
| `ARGV[1..3]` | 券 id、用户 id、订单 id —— **订单 id 也是在 Java 侧提前生成好一起传进来的** |
| `stockKey = "seckill:stock:"..voucherId` | 库存 key，由前缀**拼接**出来（不是 `KEYS`） |
| `orderKey = "seckill:order:"..voucherId` | 一人一单的 Set key（每张券一个 Set，成员是 userId） |
| `if tonumber(get stockKey) <= 0 then return 1` | 库存 ≤ 0 → 返回 **1** |
| `if sismember(orderKey, userId) == 1 then return 2` | 用户已在 Set 里 → 返回 **2** |
| `incrby stockKey -1` / `sadd orderKey userId` | 扣库存 / 记账（把 userId 加进已下单集合） |
| `xadd stream.orders * ...` | **发消息**，`*` 让 Redis 自动生成消息 id，最后 `return 0` |

**三个返回值的含义：**

| 返回值 | 含义 | Java 侧动作 |
| --- | --- | --- |
| **0** | 有资格，库存已扣、userId 已记账、消息已投递 | `Result.ok(orderId)` |
| **1** | 库存不足 | `Result.fail("库存不足")` |
| **2** | 用户重复下单 | `Result.fail("用户重复下单")` |

对应 `VoucherOrderServiceImpl.java:200-203`：

```java
if(result!=0)
{
    return Result.fail(result==1?"库存不足":"用户重复下单");
}
```

> **为什么「库存 + 一人一单 + 发消息」必须放进同一个 Lua？**
>
> 1. **前后两步之间有 TOCTOU 竞争**。若「判库存」和「扣库存」是两条命令，两个请求可能同时读到 `stock=1`
>    各扣一次 → 扣成 `-1` 超卖。Lua 整段原子，中间插不进别人。
> 2. **「发消息」必须和「记账」原子**。若先 `sadd` 再在 Java 侧另发 `XADD`，两步之间的任何崩溃都会造成
>    「Redis 认为这人下过单，但队列里没消息」→ **永久丢单**。并进脚本 = 要么全发生，要么全不发生。
> 3. **减少往返**：一次网络往返干完四件事。

**这段脚本和 09-24 那篇相矛盾**：09-24 刚讲过「Lua 里**不要拼 key**，必须走 `KEYS`」，
而这里 `stockKey` / `orderKey` 恰恰是**字符串拼接**（`seckillOrder.lua:11,13`），
Java 侧传的也是 `Collections.emptyList()`（`VoucherOrderServiceImpl.java:194`，空 KEYS）。**单机没事，上集群直接报跨 slot 错** —— 见易错点 4。

### 4.2 `seckillVoucher`（请求线程，当前活代码）

```java
public Result seckillVoucher(Long voucherId){
    Long userId = UserHolder.getUser().getId();
    Long orderId = redisIdWorker.nextId("order");

    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //1.执行lua脚本
    Long result = stringRedisTemplate.execute(
            SECKILL_ORDER_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(), userId.toString(),String.valueOf(orderId)
    );

    //判断result是否为0

    if(result!=0)
    {
        return Result.fail(result==1?"库存不足":"用户重复下单");
    }


    //返回订单id
    return Result.ok(orderId);
}
```

- **订单 id 在跑 Lua 之前就生成好**（`:188`），并作为 `ARGV[3]` 传进脚本，由脚本负责 `XADD` 投递 ——
  这样「返回给前端的 id」和「队列里的 id」是同一个，用户才能拿它去查。
- **`proxy` 只赋值、这里不用**。当前活代码里请求线程**不再**自己下单，`proxy` 是留给后台消费线程用的（`:176`）。
- 脚本三段式初始化（`:65-70`），和 09-24 的 `unlock.lua` 一样：`setLocation(new ClassPathResource("seckillOrder.lua"))` + `setResultType(Long.class)`。

### 4.3 `VoucherOrderHandler.run`（消费线程主循环）

```java
private class VoucherOrderHandler implements Runnable
{
    String queueName = "stream.orders";
    @Override
    public void run() {
        while (true)
        {
            try {
                //获取消息队列的信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed())
                );
                //判断消息获取是否成功
                if(list==null||list.isEmpty())
                {
                    //失败,进入循环
                    continue;
                }
                //成功,解析信息,下单
                MapRecord<String, Object, Object> record = list.get(0);
                RecordId id = record.getId();
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                HandleVoucherOrder(voucherOrder);

                //ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);

            } catch (Exception e) {
                log.error("处理订单异常",e);
                //出现异常,调用异常处理函数
                HandlePendingList();
            }

        }
    }
```

| 代码 | 含义 |
| --- | --- |
| `Consumer.from("g1", "c1")` | 组名 `g1`、消费者名 `c1`（**组要先建出来，见易错点 1**） |
| `count(1).block(2s)` | 一次最多读 1 条，队列空时**最多阻塞 2 秒**；`isEmpty()` 时 `continue` 回循环顶部继续等 |
| `ReadOffset.lastConsumed()` | 等价 `>`：**只读「从未投递给本组」的新消息** |
| `BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true)` | 把 `{voucherId,userId,id}` 填回实体，`true` = 忽略大小写 |
| `acknowledge(queueName, "g1", id)` | `XACK`：**处理成功之后**才确认 |

### 4.4 `HandlePendingList`（异常兜底）

```java
private void HandlePendingList() {
    while (true)
    {
        try {
            //获取消息队列的信息
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(queueName, ReadOffset.from("0"))
            );
            //判断消息获取是否成功
            if(list==null||list.isEmpty())
            {
                //说明list没有待处理信息,退出
                break;
            }
            //失败,解析信息,下单
            MapRecord<String, Object, Object> record = list.get(0);
            RecordId id = record.getId();
            Map<Object, Object> value = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
            HandleVoucherOrder(voucherOrder);

            //ACK确认
            stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);

        } catch (Exception e) {
            log.error("处理PendingList订单异常",e);
            //出现异常,调用异常处理函数,进入循环继续读取
            try {
                //睡眠防止频繁循环
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }

    }
}
```

**和 `run` 只有两处不同，但这两处就是「消费者组」的全部精髓：**

| | `run`（正常） | `HandlePendingList`（异常） |
| --- | --- | --- |
| 读取偏移 | `ReadOffset.lastConsumed()` → `>` | `ReadOffset.from("0")` → 从 pending list 头读 |
| 阻塞 | `block(2s)` | **不阻塞**（有就返回，没有就空） |
| 读不到时 | `continue`（接着等） | **`break`**（pending list 空了，回主循环）；出错则睡 1 秒接着重试 |

**`XREADGROUP` 的 `>` 和 pending list 的区别：**

- **`>`（`lastConsumed()`）**：只返回「**本组从未投递过**」的新消息。一条消息只要被投递过一次（哪怕没 ACK），`>` 就**再也不会把它给回来**。
- **pending list（PEL）**：每条被投递给某消费者、但**尚未 `XACK`** 的消息都留在组里的「待确认清单」。用**具体 id**（这里是 `0`）去读，读的就是这份清单里「id > 0」的第一条。

**为什么异常时要读 pending list？** 因为**出异常的那条消息已经投递过了**（它进了 PEL），此时再用 `>` 去读，
**它永远不会再出现** —— 重试的入口不在 `>` 而在 PEL。所以 `catch` 里必须切到 `ReadOffset.from("0")`
把没确认的消息捞回来重做。这就是「**至少消费一次（at-least-once）**」的实现。

**为什么 `acknowledge` 要放在业务处理之后？** 顺序是 `HandleVoucherOrder(...)`（`:111`）→ `acknowledge(...)`（`:114`）。

- **先 ACK 再处理**：ACK 一发出消息就离开 PEL。此时崩溃 / 下单抛异常 → 这条消息**再也捞不回来**，订单**永久丢失** —— 变成「至多一次」，秒杀不可接受。
- **后 ACK**：只有「下单完整跑完没抛异常」才确认。中途挂了 → 消息留在 PEL → 下次 `HandlePendingList` 重放 → **最多重复，不会丢**。重复由 `creatVoucherOrder` 里的一人一单查询兜住（`:294`）。

> 一句话：**ACK 的位置决定了「丢消息」还是「重复消息」**，秒杀宁可重复（有幂等校验）也不能丢。

### 4.5 `HandleVoucherOrder`（消费线程里加锁 + 调事务方法）

```java
private void HandleVoucherOrder(VoucherOrder order) {
    RLock lock = redissonClient.getLock("order:" + order.getUserId());

    if (!lock.tryLock()) {
        //不成功,直接报错
        log.error("用户不能重复下单");
        return;
    }
    //成功
    try{
       proxy.creatVoucherOrder(order);
    }catch (Exception e)
    {
        throw new  RuntimeException (e);
    }
    finally {
        lock.unlock();
    }

}
```

- **锁的粒度是「用户」**（`order:{userId}`），用 Redisson 的 `RLock` + 无参 `tryLock()`：抢不到**立刻返回 false**（无参版不等），抢到了由 Redisson 看门狗自动续期。
- **`proxy` 是必须的**（`:176`）。`creatVoucherOrder` 上有 `@Transactional`，而这里是在**消费线程**里调用 —— 若用 `this.creatVoucherOrder(...)` 直接调，就绕过了 Spring 的 AOP 代理，**事务会失效**（课程 6.3 注释专门标了）。`proxy` 来自 `AopContext.currentProxy()`；`HmDianPingApplication.java:10` 的 `@EnableAspectJAutoProxy(exposeProxy = true)` 就是为它开的。异常时 `throw new RuntimeException(e)` 是为让事务回滚、并把异常抛回 `run()` 触发重试，`finally` 里 `unlock()` 保证锁一定还。

### 4.6 `creatVoucherOrder`（真正落库，`@Transactional`）

```java
@Transactional
    public void creatVoucherOrder(VoucherOrder order)
    {
        Long userId = order.getUserId();

        Long voucherId = order.getVoucherId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();

        if(count>0)
        {
             log.error("每个用户只能下一单!");
             return;
        }

        //扣减库存,考虑到超卖,使用乐观锁()
        //方法一,如果cas中stock和当前对象get的stock不一致就失败->导致多线程抢一个秒杀券会有高失败率
        //业务上我们只需要保证stock大于0,就可以继续购买,因此采用方案二
        //boolean success = iSeckillVoucherService.update()
        // .setSql("stock = stock - 1").eq("voucher_id", voucherId).eq("stock",voucher.getStock())
        // .update();

        boolean success = iSeckillVoucherService.update().
                setSql("stock = stock -1 ")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success)
        {
            log.error("库存不足");
           return;
        }

        //创建订单

        save(order);
        return ;
    }
```

- **这段就是「旧版同步下单」里保留下来的落库逻辑**，只是现在跑在消费线程里。
- **一人一单在 DB 侧是「先查再插」**（`:294-300`），**不是靠唯一索引** —— `tb_voucher_order` **没有 `(user_id, voucher_id)` 唯一索引**（`hmdp.sql:1266-1278` 只有 `PRIMARY KEY(id)`）。见易错点 8。
- **扣库存是「乐观锁」第二种写法**：`gt("stock", 0)` 而非 `eq("stock", 原值)`。注释写得很清楚：CAS 版在多人抢同一张券时**失败率极高**，业务上只要保证 `stock > 0` 就够。
- **`@Transactional` 保证「扣库存 + 建订单」要么一起成、要么一起败**。`save` 抛异常 → `update` 一起回滚 → 消息留在 PEL → 下次从干净状态重来。

### 4.7 被注释掉的两套历史版本

**（一）阻塞队列版**（课程 6.3），`VoucherOrderServiceImpl.java:210-239`，注意每行开头都是 `//`：

```java
//        //不为0,放入阻塞队列
//        //TODO
//        //将信息封装成订单
//        Long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
```

它依赖的字段也被注掉了（`:76`）：`//private BlockingQueue<VoucherOrder> queue = new ArrayBlockingQueue<>(1024*1024);`；上面片段之后紧跟着 `queue.add(voucherOrder)` → `proxy = ...` → `return Result.ok(orderId)`（`:235-238`）。
**这段「全注释」，一行都没在跑** —— 问题课程总结过：内存限制 + 数据安全（进程挂了队列里的单全丢）。

**（二）同步下单 + Redisson 锁版**（`VoucherOrderServiceImpl.java:242-286`）也是**整段注释**，就是 `1~6` 步串行走完的那套。

> 三套版本一句话对照：同步 + Redisson 锁 → **注释**；阻塞队列 → **注释**；**Stream 消费者组 → 活代码**。

## 五、消息队列方案的演进

| 方案 | 核心命令 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **List** | `LPUSH` + `BRPOP` | 有 Redis 持久化、不受 JVM 内存限制、可保序 | **没有 ACK**（弹出即消失，崩了就丢）、只支持单消费者、无回溯 |
| **PubSub** | `SUBSCRIBE` / `PUBLISH` | 支持多生产、多消费（广播） | **不持久化**、订阅者掉线即丢、堆积有上限、无 ACK |
| **Stream（单消费者）** | `XADD` / `XREAD` | 可回溯、可阻塞读、可多消费者读同一条 | 用 `$` 做起点会**漏读**，无 ACK，无重试 |
| **Stream（消费者组）** | `XADD` / `XREADGROUP` / `XACK` / `XPENDING` | 可回溯、多消费者争抢、可阻塞、**有 ACK**、**不漏读**、至少一次 | 复杂：要自己管 PEL、自己建组、自己处理毒消息 |

- **List 解决了「内存」（数据落 Redis），没解决「可靠」** —— `BRPOP` 取走就没了，消费者挂了消息**找不回来**。
- **PubSub 解决了「多订阅者」，牺牲了持久化** —— 本质是「实时广播」，没人在线就没了。**Stream 消费者组才是「真正的消息队列」**：消息不会被取走，只被「投递」并登记进 PEL，`XACK` 后才算完成；没确认的谁都能从 PEL 捞回来重做。

## 六、易错点

1. **消费者组 `g1` 在代码里根本没人创建**。全项目搜 `createGroup` / `XGROUP` → **零命中**（`seckillOrder.lua` 只 `XADD` 建了 stream，但 stream 存在 ≠ 组存在）。**第一次跑必须先在 redis-cli 手动建组**：`XGROUP CREATE stream.orders g1 0 MKSTREAM`。否则第一次 `read` 抛 `NOGROUP` → 掉进 `catch` → `HandlePendingList()` → **里面那句 `read` 同样抛 `NOGROUP`** → 它内部 `while(true)` 的 `break` 只在「读到空」时触发，报错永远走不到 → **消费线程被永久卡死在 `HandlePendingList` 里，每 1 秒刷一条错误日志**（`:125-163`）。这是「**服务没崩、但订单一条也不消费**」的隐蔽故障。

2. **`HandlePendingList` 是没有出口的毒消息陷阱**。只要有一条消息**每次处理都失败**（数据非法导致 `save` 永远抛异常），`while(true)` + `catch` 里 `sleep(1000)` + 再读同一条 = **无限重试**，日志刷屏、线程永不回主循环。课程版睡 20ms，本质一样。

3. **`HandleVoucherOrder` 抢不到锁时直接 `return`**（`:169-173`），**不抛异常** → 回到 `run()` 后**照样 `acknowledge`**（`:114`）。这条消息被「安静地丢掉」：既没下单、也没进 PEL。虽然单消费线程下几乎不会抢锁失败，但语义是「静默丢单」，不合理。

4. **Lua 里的 key 是字符串拼的，不是 `KEYS` 传的**（`seckillOrder.lua:11,13`，Java 侧传空 KEYS `:194`）。和 09-24「Lua 里不要拼 key」直接冲突。**单机没事，集群直接报错**（跨 slot）。

5. **`tonumber(redis.call("get", stockKey))` 在库存 key 不存在时会报错**。`GET` 不存在的 key 返回 **Lua 的 `false`**，`tonumber(false)` = `nil`，而 `nil <= 0` 会**直接抛错**（`attempt to compare nil with number`）。也就是说：**只要 `seckill:stock:{id}` 没被 `addSeckillVoucher` 写进去**（Redis 被清过、券直接插库），这个 Lua 就 500，**「1 = 库存不足」并不会生效**。

6. **订单 id 是 18 位 Long，前端 JS 丢精度**。`Result.ok(orderId)` 把 Long 塞进 JSON，而 JS 的 `Number.MAX_SAFE_INTEGER` 是 `2^53-1 ≈ 9×10^15`（16 位），接不住 18 位的数。要传字符串或让前端用 `BigInt` 接。

7. **`result` 是 `Long`，`if(result!=0)` 依赖自动拆箱**。脚本执行失败返回 `null` 时，这步会 **NPE**，而不是给出可读的「脚本执行失败」。

8. **`tb_voucher_order` 没有 `(user_id, voucher_id)` 唯一索引**（`hmdp.sql:1266-1278`）。一人一单**只靠代码**（Redis Set + DB count 查询）兜着。换个入口写入、或 Redis 被清空导致 Set 丢失，DB 侧没有任何约束能拦住重复订单。

9. **`Executors.newSingleThreadExecutor()` 的老毛病**（`:74`）：无界队列、字段不是 `static final`、没有 `@PreDestroy` 关闭 —— 停机时线程不优雅退出（和 09-21 缓存篇 `CacheClient` 的线程池同类问题）。

10. **`VoucherServiceImpl.addSeckillVoucher` 有 `@Transactional`，但 Redis 写入在事务提交之前**（`:57`）。事务回滚时 MySQL 没这张券、**Redis 里却留下库存 key**，两边不一致。而且这个 `set` **没有 TTL**，key 永久驻留。

11. **`VoucherOrderServiceImpl.java:17` 有 `import com.sun.xml.internal.bind.v2.TODO;`**。这是 JDK **内部** API（`com.sun.internal`），JDK 9+ 模块化下会直接报错，而且是**完全用不到的导入**。

12. **`creatVoucherOrder` 缺 `@Override`**（`IVoucherOrderService.java:19` 声明了它）；`creat` 这个拼写本身也是错的（应为 `create`），**接口、实现、调用三处必须一致**才能编译过。

## 七、不依赖前端的验证方法

整条链路都能用 `redis-cli` + `curl` 观测，不需要前端页面。

**第 0 步：先建组**（易错点 1，不建则整条链路全废）：`XGROUP CREATE stream.orders g1 0 MKSTREAM`（已存在会报 `BUSYGROUP`，忽略），再用 `XINFO GROUPS stream.orders` 确认组在。

**第 1 步：建一张秒杀券**（`POST /voucher/seckill`，body 带 `type:1, stock:5, beginTime, endTime`），然后 `redis-cli GET seckill:stock:{voucherId}` 期望 `"5"`。

**第 2 步：抢一次，观察三个 key**：

```bash
redis-cli GET      seckill:stock:{voucherId}             # 5 → 4
redis-cli SISMEMBER seckill:order:{voucherId} {userId}   # → 1
redis-cli XLEN     stream.orders                         # +1
redis-cli XPENDING stream.orders g1                      # 看 pending 计数
```

**第 3、4 步：看消费结果 + 查库**：`XINFO GROUPS stream.orders`（pending 应回到 0，`last-delivered-id` 前进）、
`XRANGE stream.orders - + COUNT 10`（看消息体 `{voucherId, userId, id}`）；再核对 `tb_voucher_order`（有且只有一条）和 `tb_seckill_voucher.stock`（和 Redis 扣减一致）。

**判读标准：**

| 现象 | 含义 |
| --- | --- |
| `XPENDING` 一直不为 0，日志刷「处理PendingList订单异常」 | 消息一直处理失败（毒消息 / 数据问题）→ 易错点 2 |
| 日志刷 `NOGROUP` | 组没建 → 易错点 1 |
| Redis `stock` 减了、DB 查不到订单 | **Redis 扣减成功、DB 落库失败** → 见 8.8 |
| `XLEN` 涨了但 `XPENDING` 不动、DB 无订单 | 消费线程根本没在跑（挂 / 卡死） |

**并发压测**（验证不超卖）：`ab` / `jmeter` 打 `POST /voucher-order/seckill/{id}`，结束后核对 `stock_redis + 订单数 == 初始库存`，且**同一 userId 只出一个订单**。

## 八、可以更好的地方

### 8.1 启动时自动建消费者组

**现状**：`g1` 只在文档里、不在代码里（易错点 1）。**问题**：新环境部署必忘，忘了就是「服务活着但一单不消费」。
**怎么改**：在 `init()` 里（起消费者之前）建组，容忍 `BUSYGROUP`：

```java
try {
    stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
} catch (Exception e) {
    log.info("消费者组已存在，跳过创建");   // BUSYGROUP
}
```

### 8.2 给毒消息加退路（死信 / 重试上限）

**现状**：`HandlePendingList` 对失败消息无限重试（易错点 2）。**怎么改**：用 `XPENDING` 拿每条消息的**投递次数**，超过阈值就 `XADD` 到 `stream.orders.dlq`（死信队列）再 `XACK` 原消息，让主循环继续。

### 8.3 把 stream 名 / 组名做成常量

**现状**：`"stream.orders"` 硬编码在 `VoucherOrderServiceImpl.java:88` 和 `seckillOrder.lua:27`；组名 `"g1"`、消费者名 `"c1"` 散在代码里；`RedisConstants` 里**没有任何相关常量**。**问题**：Lua 改了前缀、Java 忘了改，就是**静默错位**。**怎么改**：加到 `RedisConstants`（`SECKILL_STREAM_KEY`、`SECKILL_GROUP` 等）。

### 8.4 一人一单补上数据库唯一索引

**现状**：只有代码层校验（易错点 8）。**怎么改**：`ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);` 让 DB 做最后一道防线，`save` 撞唯一键就回滚重试。

### 8.5 用对账补最终一致性

**现状**：Redis 已扣减 / 已入 Set，但 DB 落库失败时两边长期不一致（见 8.8）。**怎么改**：加**对账定时任务**，定期比对「Redis 已下单集合」和「DB 订单表」的差集，把 Redis 有、DB 无的记录补出来重发/人工介入。这是把「至少一次」从**消息层**补齐到**业务层**的关键。

### 8.6 清理历史代码

**现状**：两套注释掉的实现（`:210-239`、`:242-286`）+ 一行 JDK 内部 import（易错点 11）。**怎么改**：历史版本挪到 git 历史 / 单独分支，主分支只留活代码；删掉无用 import。但**作为学习轨迹保留是有价值的** —— 只要清楚哪套在跑。

### 8.7 优先级

| 改动 | 成本 | 收益 | 建议 |
| --- | --- | --- | --- |
| 启动时自动建组（8.1） | 五行 | **高**（不建=整条链路全废） | **必做** |
| 删掉 JDK 内部 import（易错点 11） | 一行 | 高（避免 JDK9+ 编译失败） | **必做** |
| 一人一单唯一索引（8.4） | 一条 SQL | 高（防重复订单） | 建议做 |
| 毒消息死信/重试上限（8.2） | 中 | 高（防线程卡死刷日志） | 建议做 |
| 对账任务补一致性（8.5） | 高 | 高（根治两边不一致） | 看情况 |
| stream 名抽常量（8.3） | 三行 | 中 | 建议做 |
| 订单 id 传字符串（易错点 6）、线程池换 Spring（易错点 9）、清理注释代码（8.6） | 低 | 中/低 | 看情况 |

### 8.8 「最终一致性」到底是什么性质

必须把「Redis 成功、DB 失败」这个窗口说清楚：

```text
请求线程:  Lua 成功（stock-1、sadd userId、XADD 成功） → 返回 orderId
                    ↓
消费线程:  XREADGROUP 拿到消息 → creatVoucherOrder 落库 ──✗ 抛异常
                    ↓
           消息留在 PEL（没 ACK）→ HandlePendingList 重试 → 直到成功
```

| 情况 | 结果 | 一致性 |
| --- | --- | --- |
| DB 失败是**暂时性**的（超时、瞬断） | PEL 重试 → 最终落库 | **最终一致** ✅ |
| DB 失败是**永久性**的（数据非法、表被删） | PEL 无限重试（或死信）→ **Redis 已扣的库存补不回来**，用户拿着 orderId 永远查不到订单 | **不一致，且不会自愈** ❌ |
| 消费前进程崩溃，消息已投递 | 消息在 PEL → 重启后 `HandlePendingList` 重放 | **最终一致** ✅ |

还有一条更隐蔽的：`creatVoucherOrder` 里**「查 count > 0 就直接 return」**（`:296-300`）—— 如果 Redis 被清过（Set 丢了）而 DB 里已有该用户订单，用户会**再次通过 Lua 校验**、**Redis 再扣一次库存**，然后消费时 DB `count>0` → 直接返回、消息被 ACK。**结果是库存白扣一次、订单没多一条。** 只有靠 8.5 的对账才能发现。

**一句话**：这条流水线是「**以 Redis 为准的资格判定 + 至少一次的落库重试**」，在「DB 故障可恢复」的前提下才是最终一致的；DB 永久失败、或 Redis 状态被外力改过时**不会自愈**。它换来极高的吞吐（请求线程只碰 Redis），代价是把一致性风险**转移到了运维和对账上**。
