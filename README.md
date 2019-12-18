## hotswap

hotswap是一个可用于JVM中 class 字节码热更新工具，支持线上和本地开发环境的热部署

## 开发状态

hotswap目前已经在线上部署使用，但并未进行非常全面的测试，如需要线上部署可自行进行充分测试。

目前支持线上部署和本地开发环境部署，采用java 1.5+ 提供的 `java.lang.instrument.Instrumentation` 类的 `redefineClasses` 方法实现，本身功能不能超出此类的限制，具体说明可以参照 java doc 文档。

### 使用示例

公共参数，公共参数都通过系统参数的方式传递，也可以通过后缀（如：period）添加到 agent参数后面

```
ilivoo.hotSwap.period			热交换后台线程运行周期
ilivoo.hotSwap.keepTime			热更新文件，更新后保留的时间
ilivoo.hotSwap.reloadDirs		热更新指定的路径
ilivoo.hotSwap.develop			是否是开发模式
ilivoo.hotSwap.recursive		热更新路径是否递归查找
ilivoo.hotSwap.md5Compare		class文件对比，默认使用文件的lastmodified时间
```

- 本地开发环境

  ```
  -javaagent:target/hotswap-1.0.jar=develop=true,period=10000,reloadDirs=target/classes
  ```

- 线上环境

  ```
  -javaagent:target/hotswap-1.0.jar=reloadDirs=hotdir
  ```

- 测试工具，用来简单测试hotswap工具的测试类

  ```
  com.ilivoo.hotswap.TestTool
  ```

## 开发计划

计划在后期版本中加入功能和优化

- hotswap 动态attach到某个java进程