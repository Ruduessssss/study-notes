### Spring Bean 的扫描与注册机制

在 Spring 框架中，将对象交给 IoC 容器管理的过程称为“注册”，而 Spring 寻找这些对象的过程称为“扫描”。针对自己写的代码和第三方库的代码，Spring 提供了不同的处理方案。

#### 1. Bean 的扫描机制 (`@ComponentScan`)

- **默认行为**：Spring Boot 启动类上的 `@SpringBootApplication` 注解内置了 `@ComponentScan`。它默认的扫描范围是**当前启动类所在的包及其所有子包**。

- **扩大扫描范围**：如果有些 Bean 放在了启动类所在包的外部，默认是扫描不到的。此时可以在启动类上显式添加 `@ComponentScan` 来指定需要扫描的包路径。

  Java

  ```
  // 覆盖默认扫描范围，包含原本的包和外部的包
  @ComponentScan({"com.myproject.app", "com.otherproject.utils"})
  @SpringBootApplication
  public class MyApp { ... }
  ```

#### 2. 自定义代码的 Bean 注册 (自身业务类)

对于我们自己编写的类，直接在类名上加注解即可：

- **`@Component`**：最基础、最广泛的组件注解。
- **衍生注解（分层说明）**：
  - **`@Controller`**：用于控制层（Web 层，接收请求）。
  - **`@Service`**：用于业务逻辑层。
  - **`@Repository`**：用于数据访问层（Dao 层）。

#### 3. 第三方 Bean 的注册 (`@Bean`)

当我们需要将第三方依赖（如 `RestTemplate`、`DruidDataSource`）交给 Spring 管理时，因为**无法修改别人的源码去加 `@Component`**，只能使用 `@Bean`。

- **使用方式**：在 `@Configuration` 配置类中编写一个新建该对象并返回的方法，在方法上加上 `@Bean`。

- **Bean 的命名与获取**：

  - 默认名字：就是该方法的**方法名**。获取时使用 `applicationContext.getBean("方法名")`。
  - 自定义名字：通过 `@Bean("想要的名字")` 来重命名。

- **依赖注入（参数注入）**：如果这个第三方 Bean 的创建过程需要用到容器中的另一个 Bean，直接**在方法参数中声明即可**，Spring 会自动为你注入。

  Java

  ```
  @Configuration
  public class MyConfig {
  
      // 1. 默认名字是 "restTemplate"
      @Bean 
      public RestTemplate restTemplate() {
          return new RestTemplate();
      }
  
      // 2. 自定义名字，且需要注入另一个 Bean (RedisConnectionFactory)
      @Bean("myRedisTemplate") 
      public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
          RedisTemplate<String, String> template = new RedisTemplate<>();
          template.setConnectionFactory(factory); // 自动装配进来的参数
          return template;
      }
  }
  ```

#### 4. 高级批量注册 (`@Import` 与 `ImportSelector`)

当需要快速导入其他配置，或者开发自定义的 Starter (自动装配) 时，`@Bean` 显得不够灵活，这时会用到 `@Import`。

- **直接导入普通类/配置类**：`@Import(MyService.class)`。Spring 会自动将其注册为 Bean，Bean 的名称默认为全限定类名。

- **导入 `ImportSelector` 实现类 (自动装配的核心)**：当需要根据某些条件动态导入大量 Bean 时，可以编写一个类实现 `ImportSelector` 接口，在 `selectImports` 方法中返回一个包含大量类名的 String 数组。Spring 会自动将这数组里的所有类全部注册为 Bean。

  Java

  ```
  // 动态决定要加载哪些类的全限定名
  public class MyImportSelector implements ImportSelector {
      @Override
      public String[] selectImports(AnnotationMetadata importingClassMetadata) {
          return new String[]{"com.example.DemoService1", "com.example.DemoService2"};
      }
  }
  
  // 在配置类上直接导入 Selector
  @Import(MyImportSelector.class)
  @Configuration
  public class AppConfig { }
  ```