# 运动安全核心（实验性、尚未接硬件）

当前阶段只有可独立测试的 C++17 安全决策器，**没有舵机固件、PWM输出或手机控制入口**。Android、家庭服务均不能直写电机。这样保留 P2 机械能力的协议边界，不把主机模拟当成真实运动验收。

适配 MCU 时，`received_ms` 必须由 MCU 的单调时钟在接收时赋值，不能接受发送端时间；最大有效期500ms，严格递增序号；失联自动断输出，物理限位、过流、单调时钟异常锁定急停；只能经实体确认清除，清除后还需再次启用。速度斜坡、机械限位、碰撞力和电源急停须由选定机构补充验证，未经验证不得接触儿童。

```sh
c++ -std=c++17 -Wall -Wextra -Werror -Imotion-firmware/include motion-firmware/tests/safety_test.cpp -o /tmp/robot-safety-test
/tmp/robot-safety-test
```
