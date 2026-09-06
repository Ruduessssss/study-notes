## 线程安全分析

结合前面探讨的一系列经典踩坑案例，判断一段代码是否线程安全，本质上是在排查“多个线程是否会同时对同一个共享、可变的状态进行非原子性的修改”。

您可以基于以下五个维度，像排雷一样逐步审查您的代码：

**1. 审查运行环境与变量作用域（是否产生“共享”）**

- **安全区（线程封闭）：** 局部变量（方法内定义的变量）存在于每个线程私有的栈帧中。只要它不传出方法，就是绝对安全的。同样，每次调用都 `new` 出来的新对象（不作为成员变量共享）也是安全的。
- **高危区（单例共享）：** 在 Web 开发中，Servlet、Spring 的 Controller / Service / `@Component` 切面，默认都是**单例**的。它们内部声明的成员变量（如 `Connection`、计数器 `count`、共享 `Map`）会被所有并发请求共享，极易引发安全问题。

**2. 审查对象的可变性与 `final` 陷阱（状态是否“可变”）**

- **不可变对象绝对安全：** 如 `String`，其内部状态在创建后无法修改，多线程同时读取是安全的。
- **警惕可变对象：** 像 `Date`、`HashMap`、`SimpleDateFormat` 这些对象内部包含可修改的状态，被多线程共享时是不安全的。
- **看破 `final` 的伪装：** `final` 只能保证**引用地址**不变，但阻止不了别的线程修改该对象内部的属性（例如 `final Date` 依然可以被调用 `setTime`）。

**3. 审查操作的原子性（是否包含“复合操作”）**

- 即使是基础的数据类型，像 `count++` 这种操作也包含了“读取-计算-写入”三个步骤。在没有加锁或未使用 `AtomicInteger` 等并发类的情况下，多线程执行必然会导致“丢失更新”。

**4. 审查变量是否“逃逸”（防范外星方法）**

- 一个原本安全的局部变量，如果作为参数传递给了其他未知方法（特别是可以被子类重写的“外星方法”），或者被添加到了全局集合中，它的引用就“逃逸”出去了。如果有其他后台线程拿到了这个引用并进行修改，原本的安全就会被破坏。

**5. 审查锁对象的稳定性（锁是否“同一”）**

- 当使用 `synchronized` 进行同步时，必须保证所有线程竞争的是**内存地址绝对相同的同一个对象**。
- 切忌使用包装类（如 `Integer`、`Boolean`）或动态改变的字符串作为锁对象。一旦发生修改（如 `i++`），变量会指向新的对象引用，导致不同线程拿到不同的锁，同步机制彻底失效。





## Monitor/监视头

### 1. 对象头的基本组成

在 JVM 中，每个对象除了保存我们自己定义的实例数据外，还会自带一个“对象头”。以 32 位虚拟机为例：  

- **普通对象**：对象头占 64 bits，由 **Mark Word** (32 bits) 和 **Klass Word** (32 bits，类型指针，指向该对象的类元数据) 组成。  
- **数组对象**：对象头占 96 bits，除了 Mark Word 和 Klass Word，还多了一个 32 bits 的 **array length** (数组长度)。  

普通对象  

Plaintext

```
|--------------------------------------------------------------|
|                     Object Header (64 bits)                  |
|------------------------------------|-------------------------|
|        Mark Word (32 bits)         |    Klass Word (32 bits) |
|------------------------------------|-------------------------|
```

|      |      |
| ---- | ---- |

其中 Mark Word 结构为  

Plaintext

```
|-------------------------------------------------------|--------------------|
|                  Mark Word (32 bits)                  |       State        |
|-------------------------------------------------------|--------------------|
| hashcode:25         | age:4 | biased_lock:0 | 01      |       Normal       |
|-------------------------------------------------------|--------------------|
| thread:23 | epoch:2 | age:4 | biased_lock:1 | 01      |       Biased       |
|-------------------------------------------------------|--------------------|
|               ptr_to_lock_record:30         | 00      | Lightweight Locked |
|-------------------------------------------------------|--------------------|
|               ptr_to_heavyweight_monitor:30 | 10      | Heavyweight Locked |
|-------------------------------------------------------|--------------------|
|                                             | 11      |   Marked for GC    |
|-------------------------------------------------------|--------------------|
```





### 2. Mark Word 的核心作用

**Mark Word** 是对象头中极其关键的部分，它就像是对象的“身份证”和“状态栏”，用来存储对象自身的运行时数据。 它包含的信息会随着对象状态的改变而动态变化，主要包括：  

- 对象的 `hashcode`。  
- 垃圾回收的分代年龄 (`age`)。  
- 偏向锁标记 (`biased_lock`)。  
- **锁状态标志位 (State)**。  

### 3. 锁状态切换与 Monitor 的引出

Mark Word 末尾的两位数字决定了当前对象处于什么锁状态：  

- **`01`**：无锁状态 (Normal) 或 偏向锁状态 (Biased)。  
- **`00`**：轻量级锁状态 (Lightweight Locked)。  
- **`10`：重量级锁状态 (Heavyweight Locked)**。  









### Monitor (监视器) 的工作原理

Monitor 是 `synchronized` 实现对象锁的核心底层机制，其工作原理主要围绕 `Owner`、`EntryList` 和 `WaitSet` 三个核心区域展开：

- **初始状态**：在没有任何线程加锁时，Monitor 中的 `Owner` 属性为 `null`。
- **获取锁 (Owner)**：当某个线程（例如 Thread-2）执行到 `synchronized(obj)` 时，会将该 Monitor 的 `Owner` 设置为自己。**Monitor 中同一时刻只能有一个 Owner**，即实现了互斥。
- **竞争阻塞 (EntryList)**：在锁被 Thread-2 占用的期间，如果其他线程（如 Thread-3、Thread-4 等）也来执行 `synchronized(obj)`，它们会因为无法获取锁而进入 **`EntryList`**，此时线程状态变为 `BLOCKED`（阻塞状态）。
- **释放与唤醒**：当 Thread-2 执行完同步代码块的内容后，会释放锁（`Owner` 重新置空），并唤醒 `EntryList` 中等待的线程来重新竞争这把锁。需要强调的是，这种锁的竞争是**非公平的**（即先等待的线程未必先拿到锁）。
- **条件等待 (WaitSet)**：如果某些线程之前成功获得过锁，但由于业务条件不满足，它们会主动释放锁并进入 **`WaitSet`** 中，此时状态变为 `WAITING`。这部分主要涉及 `wait` 和 `notify` 机制。



![捕获](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\捕获.PNG)





## synchornized优化原理

### 1.轻量级锁






![c4f55a66fa20a749caa893c221d3679d](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\c4f55a66fa20a749caa893c221d3679d.png)



![af0044e7e1b1ab893a6d32083e0189c3](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\af0044e7e1b1ab893a6d32083e0189c3.png)

![12ed01af6edcfc728cbeaa78493791c6](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\12ed01af6edcfc728cbeaa78493791c6.png)

![becad97de1188c7ae3b83bc7adeaa86d](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\becad97de1188c7ae3b83bc7adeaa86d.png)







![510149413988243f7e7108d14d1f5a35](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\510149413988243f7e7108d14d1f5a35.png)



### 2.锁膨胀



![155770e0a0dc1740f3f89febc285ade7](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\155770e0a0dc1740f3f89febc285ade7.png)



![a937deb267f0a55dbb43b3cfae1c9ed2](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\a937deb267f0a55dbb43b3cfae1c9ed2.png)



![709fb13e50ba78fe447d0c05f7f2e434](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\709fb13e50ba78fe447d0c05f7f2e434.png)



### 3.自旋优化

![f44008ba88992887ccb9bf5f42de6328](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\f44008ba88992887ccb9bf5f42de6328.png)





![d9c6b2c9dd5f9eaee35c888b2d728e93](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\d9c6b2c9dd5f9eaee35c888b2d728e93.png)



### 4.偏向锁



![7036f68964f7b7b0fd40ad73ad41d5cb](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\7036f68964f7b7b0fd40ad73ad41d5cb.png)





![8454b144ae82af1e50728759cdc7c4fc](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\8454b144ae82af1e50728759cdc7c4fc.png)



#### 偏向状态



![0cb3c3d93d02d5f098b4004f772cc2cb](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\0cb3c3d93d02d5f098b4004f772cc2cb.png)



这里分别在锁之前,锁中,锁后分别打印对象头,观察最后三位,偏向锁是默认开启,且设置了无延迟后就是101.在锁住对象后,偏向锁会把线程id记录到对象头上,所以标蓝部分有变化



![a2770fff51ca5568cfa3c9f30cbbe8a1](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\a2770fff51ca5568cfa3c9f30cbbe8a1.png)



#### 撤销 

1.调用对象的hashCode会撤销该偏向锁,变成轻量级锁

2.当其他线程也会使用这个偏向锁对象后,会升级为轻量级锁





#### 批量重偏向

如果对象被多个线程访问,但是没有竞争,这时偏向线程t1的对象仍有机会重新偏向t2,重偏向会重置对象的ThreadId,当撤销偏向锁的阈值超过20,,jvm就会给后续的 **这个类的对象** 偏向到加锁线程

#### 批量撤销

当撤销偏向锁的阈值超过40,jvm会给**这个类的所有对象**设置为不可偏向,即使是新建的.



### 5.锁消除

锁消除（Lock Elimination） 是 Java 虚拟机（JIT 编译器）在运行时的一项极其智能的锁优化机制。它的核心逻辑是：如果 JVM 探测到某段代码里的锁对象绝对不可能被其他线程访问到，那么它就会在编译成机器码时，安全地把这些锁给“删掉”。

1. 核心前提：逃逸分析（Escape Analysis）
锁消除能生效，完全依赖于 JIT 编译器的“逃逸分析”技术。

JVM 会在运行时扫描代码，判断一个对象（锁）是否会“逃逸”出当前线程或当前方法的作用域。

如果一个对象只是在方法内部被 new 出来，且它的引用没有通过 return 返回出去，也没有赋值给任何全局变量，JVM 就会断定：这个对象是当前线程私有的，绝对不可能发生多线程竞争。

2. 经典代码示例
我们来看一个最直观的场景。StringBuffer 是线程安全的，它的 append 方法内部使用了 synchronized 关键字。但如果我们这样写代码：

Java
public String buildString(String s1, String s2) {
    // sb 是一个局部变量，生命周期仅限于当前方法，没有逃逸
    StringBuffer sb = new StringBuffer(); 
    
    // append 方法内部本来是有 synchronized 锁的
    sb.append(s1); 
    sb.append(s2);
    
    return sb.toString(); 
}
底层发生了什么？

按照字面意思，代码在调用 sb.append() 时，应该去执行加锁、修改 Mark Word、解锁的过程。

但在代码多次运行变“热”后，JIT 编译器介入。它通过逃逸分析发现：sb 对象只属于当前执行 buildString 的这一个线程，根本没有第二个线程能拿到这个 sb 去抢锁。

于是，JIT 在将字节码编译成本地机器码时，会直接无视并剥离掉 append 内部的 synchronized 指令。这使得它的实际运行速度几乎等同于无锁的 StringBuilder。

3. 为什么需要这个机制？
你可能会问：既然知道是局部变量，开发者直接用无锁的 StringBuilder 不就好了吗？为什么还要劳烦 JVM 去消除？

实际上，锁消除主要是为了给“隐式加锁”或“防御性编程”兜底。在复杂的业务流中，或者当你调用别人封装好的 API（如内部使用了 Vector、Hashtable 或某些保证线程安全的工具类）时，你很容易在单线程的局部作用域内无意间触发加锁。锁消除就是为了在这种场景下，悄无声息地帮你抹平这些多余的性能损耗。





## wait与notify

### 相关方法

obj.wait() 让进入 object 监视器的线程到 waitSet 等待

obj.notify() 在 object 上正在 waitSet 等待的线程中挑一个唤醒

obj.notifyAll() 让 object 上正在 waitSet 等待的线程全部唤醒

它们都是线程之间进行协作的手段，都属于 Object 对象的方法。必须获得此对象的锁，才能调用这几个方法





![decaa3daa62ae7c2747257fe86009db2](C:\Users\admin\Desktop\2026-09-06-JUC-线程安全分析,monitor,锁的优化.assets\decaa3daa62ae7c2747257fe86009db2.png)


### wait与sleep的区别

1. wait是object的方法,sleep是Thread方法

2. wait调用后会释放锁,sleep调用后不会释放锁

3. wait要强制和synchoronized一起用,sleep不需要



### wait使用建议

wait一般是该线程没有达到相关条件而无法进行,为了提高多线程运行效率而使用的.当不满足条件,wait;满足,执行代码,在用判断条件时一般以while(条件)而非if.