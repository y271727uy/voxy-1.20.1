# Claude 交接单：L19 完整层次树与节点生命周期

更新时间：2026-07-13 15:00 Asia/Taipei

## 你的角色

你负责 L19 的“数据与层次结构线”，目标是把当前 GPU shadow runtime 使用的平铺 CPU frontier roots，替换为可供 GPU traversal 消费的完整 READY/world 层次树。你不负责 OpenGL、shader、最终 renderer 接管或客户端运行验证。

主协调 Agent 会审查你的接口并与 GPT-5.5 的渲染线合并。不要自行宣布 L19 完成。

## 项目与权威来源

- 工作区：`D:\document\dev\GitHub\voxy-1.20.1`
- 目标平台：Minecraft 1.20.1 Forge 47.4.x，Java 17。
- 权威移植来源：
  - `参考项目/voxy-mc_1201`
  - `参考项目/voxy-neoforge-1.21.1`
- `distant-horizons-3.2.0b` 只能作为 1.20.1 实现思路或导入格式参考，不是 renderer 目标，也不能成为核心依赖。
- 总交接记录：先读 `nightly-handoff.txt` 顶部 L19，再开始改动。

## 当前基线

- 全量门禁：357 tests / 72 suites / 0 failures/errors/skips。
- `build`、`jar`、`reobfJar` 已通过。
- Intel OpenGL 4.6 已真实运行 native compute shadow：最高记录 833 dispatch / 830 readback。
- 可见画面仍由 CPU authoritative traversal 绘制，`uncovered=0`。
- shadow 正确性未通过：只有 1 个 matching frame，后续累计 829 mismatch，GPU 没有绘制权。
- `RenderBackendPolicy` 必须继续保持 `COMPATIBILITY_ONLY`。

## 已有核心实现

重点阅读：

- `client/core/rendering/hierachical/NodeStore.java`
- `client/core/rendering/hierachical/NodeManager.java`
- `client/core/rendering/hierachical/AsyncNodeManager.java`
- `NodeGpuAbi.java`
- `NodeGpuTraversalSnapshot.java`
- `NodeGpuUploadBatch.java`
- `GpuTraversalBuffers.java`
- `GpuTraversalShadowController.java`
- `render/mesh/SectionGeometryRegistry.java`
- `render/mesh/GpuTraversalShadowRuntime.java`，只读，用于理解当前错误边界。

当前已经实现：

- child mask 变化时保留 surviving child geometry/subtree。
- 连续 node block remap、cleaner move/free/alloc。
- position-based geometry/child pending replay，有界淘汰。
- 负坐标 parent 使用 `>>` 的 floor 语义。
- Async worker 发布不可变 encoded node spans、完整 roots、cleaner effects、released geometry ids。
- geometry id 单调不复用，render thread 延迟退休。

## 你必须完成的任务

1. 对照权威 `NodeManager`、`AsyncNodeManager`、`HierarchicalOcclusionTraverser`，设计并实现完整层次树输入，而不是把 `frameCoverage.selectedKeys()` 每帧作为互不关联的 top-level roots。
2. child existence 必须来自世界拓扑，例如 `WorldSection.nonEmptyChildren()`；不能用“当前 READY 子项”推断为空。未构建子节点应为 NULL geometry，真实空 mesh 才使用 EMPTY geometry。
3. top-level root 生命周期以 MAX_LOD/world root 为单位。普通 child eviction 只能清 geometry，不能误删仍有世界拓扑的 sibling/subtree。
4. READY section 的 geometry 更新、world child mask 更新、root add/remove 必须能乱序到达，并在 node 出现后重放；不能静默丢弃。
5. NodeStore 容量必须有界。需要明确整 root 回收条件，不能无限保留所有历史 child topology。
6. 所有 worker 输出必须通过一个不可变发布对象到 render thread。render thread 不得在 poll 后重新进入 manager capture 或等待 worker。
7. geometry release 必须与包含对应 tombstone/replacement 的 node payload 同批发布，且 exactly once。
8. 给 GPT-5.5 提供一个稳定、平台无关的层次输入 API。优先放在 `client/core/rendering/hierachical`，不要让渲染线重新推断拓扑。

## 文件所有权

你可以修改：

- `src/main/java/com/y271727uy/voxy/client/core/rendering/hierachical/**`
- `src/test/java/com/y271727uy/voxy/client/core/rendering/hierachical/**`
- 如确有必要，可在上述包新增 bridge/snapshot/payload 类型。

不要修改：

- `GpuSectionRenderer.java`
- `GpuTraversalShadowRuntime.java`
- `LwjglGpuTraversalShadowDriver.java`
- `client/render/gl/**`
- `src/main/resources/assets/voxy/shaders/**`
- `build.gradle`、运行配置、默认开关。
- `nightly-handoff.txt`。

如果现有渲染 API 不足，把所需接口写进结果回执，不要跨所有权直接修改。

## 必须覆盖的测试

- mask `001 -> 011 -> 010 -> 000`，保留/删除 geometry 与 descendants 正确。
- 多层 subtree remap 后 position index、child pointer、cleaner 与 dirty upload 一致。
- 负坐标 x/y/z parent-child 往返。
- geometry-before-node、child-mask-before-parent、root remove 与 pending update 竞争。
- root 高频加入/移除、容量耗尽、pending 淘汰，资源 exactly-once release。
- 多批 Async merge：newer node span wins、latest complete roots wins、release 不丢失。
- stop/close drain 后无 accepted command 或 release 遗漏。
- 随机 mask/churn 的 `verifyIntegrity()` 属性测试，固定随机种子。

## 验收命令

先运行 focused tests，再运行：

```powershell
.\gradlew.bat test --offline --console=plain --rerun-tasks
```

不要运行 `runClient`。客户端验证由 GPT-5.5 独占，避免两个 Agent 同时修改 quick-play/config 或误杀进程。

## 禁止事项

- 不得把 GPU backend 切到 AVAILABLE。
- 不得让 GPU queue 接管可见绘制。
- 不得修改 `renderingEnabled` 默认值。
- 不得用整棵 sibling subtree 删除来简化 child-mask 更新。
- 不得在 NodeManager 锁内执行 GL、关闭 VBO 或等待 render thread。
- 不得 reset/clean/revert 工作树。当前大量实现是未跟踪/未提交状态，均视为用户工作。

## 结果回执

完成后新建 `handoff-claude-result.md`，写清：

- 修改文件清单。
- 新 API 与线程/锁序。
- 与权威实现的对应关系。
- 测试数量和命令。
- 尚未解决的边界。
- GPT-5.5 接入时必须遵守的调用顺序。

不要直接改总交接单；由主协调 Agent 审查并合并。
