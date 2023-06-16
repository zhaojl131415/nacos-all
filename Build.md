源码编译:
1. 安装插件:
   Idea安装Protobuf Support插件后重启
2. 根目录pom.xml
   MacOS: properties中添加: <os.detected.classifier>osx-x86_64</os.detected.classifier>
   Windows: 注释上述添加的内容
3. 执行命令:
   MacOS: mvn clean install '-DskipTests' '-Dpmd.skip=true' '-Dcheckstyle.skip=true' '-Dos.detected.classifier=osx-x86_64'
   Windows: mvn clean install '-DskipTests' '-Dpmd.skip=true' '-Dcheckstyle.skip=true'
4. 配置VM
   启动类运行添加VM options: -Dnacos.standalone=true
ps: 
-DskipTests:不执行测试用例，但编译测试用例类生成相应的class文件至target/test-classes下。
-Dpmd.skip=true: 不执行java源文件检查, 主要检查空try/catch/finally/switch/if/while, 未使用过的局部变量/参数和private方法等.
-Dcheckstyle.skip=true: 不执行java源文件检查, 主要检查代码规范, 如: JavaDoc注释,命名规范,没用的import,重复代码等.
-Dos.detected.classifier=osx-x86_64 指定protobuf需要的包, mac下默认的包找不到