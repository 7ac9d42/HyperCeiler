# 重构范围与未决事项

只记录仍影响维护决策的风险和续查位置。长期约束与历史检索入口见[维护指南](refactor-guide.md)。尚未完成全量核验，也没有设备级性能、功耗或唤醒率证据。

## P0：授权、生命周期与跨端恢复

| 作用域 | 保留现状的理由与重审条件 |
| --- | --- |
| Prefs、Provider、`home_layout` | 保留 typed/聚合通知、同值写入修复远端镜像的能力，以及通用/授权快照区别。改写失效或查询节奏前，覆盖本地提交失败、无需求、旧客户端失联回退和在途结果；不能用扫层或心跳的方法名推断发生周期 IPC。参考 [桌面样式通知回归](../tests/dock-style-refresh/run.py)和[布局夹具](../tests/home-layout-native/README.md)。 |
| 最近任务卡片与状态栏 | 卡片筛选按需刷新，不等于状态栏续租获准。保留窗口移除与新场景对旧需求的撤销，以及 Native 等到新提交才重授场景的行为。改动先核对 [状态栏策略与场景夹具](../tests/home-recents-statusbar-os4/run_policy.py)及[端点授权](../tests/home-startup-snapshot/RecentsAuthorizationTest.java)；未证实真机 WMS/Binder 时序或解冻率。 |
| Dock 与原生手势/震动 | 保留 Dock 重试复用的传输 FD、手势 worker 异常后的认领撤销，以及震动队列代际隔离。重构前先验证旧生产者何时停止、FD 所有权和阻塞读如何结束；不为合并 worker 关闭仍被引用的描述符。手机网格关闭时仍须回滚补丁；布局维护不能把旧的不可绘制状态当成本轮成功答复。 |
| Weather 与 Hyos | 区分启动等待、现有 worker 节奏与 5 秒绝对 lease，不把收到回复当作续租；保留无消费者退出、权限撤销、宿主回退与 Hyos linker 门禁。若调整节奏，先验证安装、恢复、撤权与在途 Binder；宿主夹具不覆盖真实 linker 或设备唤醒。 |
| SystemUI Ambient | detach 后结果不得发布；取消 Java 任务不等于打断 JNI，也不能按 View 生命周期卸载共享 DSO。只有定位到同一 holder 的实际重复工作才考虑节流。 |
| 状态栏跟随 | 不打开现有构建门控（debug 仅 SystemUI 一侧启用，release 两端关闭）。先补偏好键与关闭路径，再验证跨成员嵌套 Hook、Framework 安装/回滚、双端回执失败和旧代死亡回调；不强制结束在途 lease。 |
| 返回手势与快速切换 | 保留聚合或来源不明的通知、在途失效检查及 WMS 侧每次动作的新鲜授权。双通知通道没有共同提交标识，不用时间窗做精确去重。 |

## P1/P2：范围与续查位置

- **系统主链**：Framework、SystemUI 和 Various/IME 只完成部分生产路径。按 [SystemUIB](../library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/app/SystemUI/SystemUIB.java) 和 [SystemFrameworkB](../library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/app/SystemFramework/SystemFrameworkB.java) 注册顺序续查。平板和跨 ClassLoader 插件另计；不以 pudding 的 Shell stub 推断完整 ROM 行为，也不在缺少宿主调用证据时扩大权限绕过或合并签名。
- **SystemUI 移动网络**：续查真实双卡时序、渲染、偏好更新和电信服务生命周期。OS4 隐藏漫游保留宿主字段回退与 binder 可见性门控；热重载只影响后续 getter，已收集的视图须按宿主生命周期重新取流。
- **PstedClipboard**：pudding、liuqin 的 SDK 37 `$5` 签名多一个末尾 `boolean[]`，现有 Hook 不匹配。若修复，保留外层 `mNotifiedUids` 去重，不伪造 `Toast.show()` 标记。`boolean[0]` 用作通知方法返回值和访问统计标记，不控制去重、统计投递或剪贴板数据返回。SDK 36 目标签名和真机挂接未核验。
- **常驻候选**：电源、电话、推送及其他常驻规则按实际唤醒和回调成本排序。PowerKeeper 的两个刷新率开关独立；未验证目标 ROM 的方法命中与写入/消费时序，不据历史补丁推断实际帧率。其余系统应用先确认生产调用者和 ROM 回退，不按目录规模或缺类删功能。
- **共享层**：仍需核对 `libhook` 的加载、清理、诊断、DexKit 与 `common` 的设置和观察者边界。实际入口以 [`java_init.list`](../library/libhook/src/main/resources/META-INF/xposed/java_init.list)、生成注册表及运行时分流为准。注解处理器的 locale 修复不证明增量生成正确；改增量前验证新增、修改、删除均能生成完整注册表。
- **应用和构建**：`app`、Core 与 Provision 仍需核验页面生命周期、缓存失效、持久化和网络取消；暂不统一 executor 或批量写日志。隐藏 API、AIDL、Native 公共层、打包及 CI 独立核验，编译成功不代表实际宿主命中或发布验收。
