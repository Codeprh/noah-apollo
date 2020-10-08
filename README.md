<img src="https://raw.githubusercontent.com/ctripcorp/apollo/master/doc/images/logo/logo-simple.png" alt="apollo-logo" width="60%">
# Apollo源码分析

# Apollo全景图

## 架构图

![image-20201007001718863](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjg2l00t1kj30jz0d0q6h.jpg)

## 部署图

![image-20201007082754006](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjggrekpxyj30re0efq5q.jpg)

## Portal交互图

![image-20201007082914591](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjggst5l21j30tv0dbdjs.jpg)

## portal配置通知给客户端

![image-20201007163633460](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjguvw67joj30nr09k3zg.jpg)

## 客户端读取配置![image-20201007220245031](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjh4b94ty7j30t80d5dj8.jpg)

##  **Portal 创建灰度**

![image-20201008101338017](https://tva1.sinaimg.cn/large/007S8ZIlgy1gjhpfqrtz7j30t80cwwjp.jpg)

# 优秀设计

## portal发布配置通知到客户端

> Admin Service 在配置发布后，需要通知所有的 Config Service 有配置发布，从而 Config Service 可以通知对应的客户端来拉取最新的配置。
>
> 从概念上来看，这是一个典型的**消息使用场景**，Admin Service 作为 **producer** 发出消息，各个Config Service 作为 **consumer** 消费消息。通过一个**消息组件**（Message Queue）就能很好的实现 Admin Service 和 Config Service 的解耦。
>
> 在实现上，考虑到 Apollo 的实际使用场景，以及为了**尽可能减少外部依赖**，我们没有采用外部的消息中间件，而是通过**数据库实现了一个简单的消息队列**。
>
> 实现方式：
>
> 1. Admin Service 在配置发布后会往 ReleaseMessage 表插入一条消息记录，消息内容就是配置发布的 AppId+Cluster+Namespace ，参见 DatabaseMessageSender 。
> 2. Config Service 有一个线程会每秒扫描一次 ReleaseMessage 表，看看是否有新的消息记录，参见 ReleaseMessageScanner 。
> 3. Config Service 如果发现有新的消息记录，那么就会通知到所有的消息监听器（ReleaseMessageListener），如 NotificationControllerV2 ，消息监听器的注册过程参见 ConfigServiceAutoConfiguration 。
> 4. NotificationControllerV2 得到配置发布的 **AppId+Cluster+Namespace** 后，会通知对应的客户端。
>
> 通知客户端实现：
>
> 1. 客户端会发起一个Http 请求到 Config Service 的 `notifications/v`2 接口，也就是NotificationControllerV2 ，参见 RemoteConfigLongPollService 。
> 2. NotificationControllerV2 不会立即返回结果，而是通过 [Spring DeferredResult](https://docs.spring.io/spring/docs/current/javadoc-api/org/springframework/web/context/request/async/DeferredResult.html) 把请求挂起。
> 3. 如果在 60 秒内没有该客户端关心的配置发布，那么会返回 Http 状态码 304 给客户端。
> 4. 如果有该客户端关心的配置发布，NotificationControllerV2 会调用 DeferredResult 的 setResult 方法，传入有配置变化的 namespace 信息，同时该请求会立即返回。客户端从返回的结果中获取到配置变化的 namespace 后，会立即请求 Config Service 获取该 namespace 的最新配置。

## SpringMVC DeferredResult设计（长轮询）

> Spring MVC 3.2开始引入了基于Servlet 3的异步请求处理。相比以前，控制器方法已经不一定需要返回一个值，而是可以返回一个java.util.concurrent.Callable的对象，并通过Spring MVC所管理的线程来产生返回值。与此同时，Servlet容器的主线程则可以退出并释放其资源了，同时也允许容器去处理其他的请求。通过一个TaskExecutor，Spring MVC可以在另外的线程中调用Callable。当Callable返回时，请求再携带Callable返回的值，再次被分配到Servlet容器中恢复处理流程。
>
> 另一个选择，是让控制器方法返回一个DeferredResult的实例。这种场景下，返回值可以由任何一个线程产生，也包括那些不是由Spring MVC管理的线程
>
> 
>
> **2.简述polling和long polling的区别？**
>
> 这里暂抛开某些场景webSocket的解决方案。
>
> 举一个生活中的列子来说明长轮询比轮询好在哪里：电商云集的时代，大家肯定都有查询快递的经历，怎么最快知道快递的进度呢？polling和long polling的方式分别如下：
>
> - polling：如果我想在两分钟内看到快递的变化，那么，轮询会每隔两分钟去像服务器发起一次快递变更的查询请求，如果快递其实是一个小时变更一次，那么polling的方式在获取一次真实有效信息时需要发起30次
> - long polling：首先发起查询请求，服务端没有更新的话就不回复，直到一个小时变更时才将结果返回给客户，然后客户发起下次查询请求。长轮询保证了每次发起的查询请求都是有效的，极大的减少了与服务端的交互，基于web异步处理技术，大大的提升了服务性能
>
> 如果在发散的触类旁通一下，long polling的方式和发布订阅的模式有点类似之处，只是每次拿到了发布的结果之后需要再次发起消息订阅
>
> 3.因为DeferredResult，所以long polling？
>
> 因为DeferredResult技术，所以使得long polling不会一直占用容器资源，使得长轮询成为可能。长轮询的应用有很多，简述下就是：需要及时知道某些消息的变更的场景都可以用长轮询来解决，当然，你可能又想起了发布订阅了，哈哈

# 优秀编码风格




