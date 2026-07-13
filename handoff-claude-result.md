# Claude L19 结果回执：世界拓扑层次输入

完成时间：2026-07-13 Asia/Taipei
角色：L19 数据与层次结构线（不接管 OpenGL / shader / renderer / 客户端运行验证）

## 摘要

交接单 8 项任务中，重型机制（child-mask 保留、连续 remap、pending 重放、不可变
`SyncBatch` 发布、exactly-once geometry release、负坐标 floor 语义）此前已在
`NodeManager` / `AsyncNodeManager` 中实现并有测试覆盖。本轮补上真正缺口——**任务 #8**：
一个稳定、平台无关的“世界拓扑 → 完整层次树”输入门面，让渲染线不再把每帧
`frameCoverage.selectedKeys()` 当作互不关联的 top-level roots，同时驱动任务 #1/#2/#3/#5。

## 修改文件清单

新增（均在我方所有权包内，未触碰任何渲染/驱动/shader/构建文件）：

- `src/main/java/com/y271727uy/voxy/client/core/rendering/hierachical/WorldHierarchyInput.java`
- `src/test/java/com/y271727uy/voxy/client/core/rendering/hierachical/WorldHierarchyInputTest.java`

未修改：`GpuSectionRenderer.java`、`GpuTraversalShadowRuntime.java`、
`LwjglGpuTraversalShadowDriver.java`、`client/render/gl/**`、`shaders/**`、
`build.gradle`、运行配置、默认开关、`nightly-handoff.txt`。

## 新 API 与线程/锁序

`WorldHierarchyInput`（`client/core/rendering/hierachical`）:

- `forAsync(AsyncNodeManager, maxLevel, maxRootCount)` — 生产用，经 worker 边界发布不可变 batch。
- `forManager(NodeManager, maxLevel, maxRootCount)` — 确定性测试用，同步执行。
- `withMutator(HierarchyMutator, …)` — 自定义 sink（未来 GPU-driven 拓扑源）。
- `updateSection(long key, byte nonEmptyChildren, int geometryId)` — 某位置世界拓扑与几何的**唯一权威**。
- `removeSection(long key)` — root：整树回收；内部节点：仅清 geometry，保留仍有世界拓扑的子树。
- `removeRoot(long rootKey)` / `rootCount()` / `containsRoot(long)` / `maxLevel()` / `maxRootCount()`。

锁序与线程约束：

- `WorldHierarchyInput` 所有公开方法在自身实例 monitor 上同步；**不**跨 mutator 调用持有其它锁。
- 它只通过 `HierarchyMutator` 下发变更，绝不触碰 GL、绝不阻塞 worker、绝不在 NodeManager 锁内等待 render thread。
- `forAsync` 下，所有变更进入 `AsyncNodeManager` 命令队列，由单 worker 应用并经 `SyncBatch` 原子发布；
  render thread 仍只 `pollSyncBatch()` → `uploadNodesAndRoots()` → 处理 `releasedGeometryIds()`，poll 后不得回到 manager capture 或等待 worker（现有契约不变）。

行为要点（对应任务）：

- #1/#8：root 以 `maxLevel` 世界 root 为单位注册；任意位置上报会把它锚定到其 `maxLevel` 祖先 root 并刷新 LRU 时序。
- #2：child existence 直接取自 `WorldSection.nonEmptyChildren()`（`updateSection` 的 `nonEmptyChildren` 参数），
  不从“当前 READY 子项”推断。未构建子节点为 `NULL` geometry；真实空 mesh 由调用方传 `EMPTY_GEOMETRY_ID`。
- #3：内部 `removeSection` 只清 geometry；整 root 生命周期由 `removeRoot` / LRU 回收，绝不误删仍有世界拓扑的 sibling/subtree。
- #4：乱序到达（geometry-before-parent、child-mask-before-parent）由 `NodeManager` 有界 pending 重放承接，节点出现后重放，不静默丢弃。
- #5：`maxRootCount` 硬上界；超限时按 access-order 回收**最久未触碰的整 root**（`LinkedHashMap` access-order）。
- #7：geometry release 仍由 `NodeManager` / `SyncBatch` 与对应 tombstone/replacement 同批、exactly-once 发布（本类不新增 release 路径）。

## 与权威实现的对应关系

- `insertTopLevelNode` / `removeTopLevelNode` ↔ `AsyncMutator.addRoot/removeRoot`。
- 权威 `NodeManager` 的 “TODO/FIXME：top level 节点应仅在有非空 children 时存在” 与 “从世界拓扑而非 READY 推断 child” 正是本门面解决的问题：`updateSection` 以世界 mask 为权威，root 以世界 root 为单位。
- 权威 `makeChildPos` / `makeParentPos`（floor 语义）↔ 我方 `NodeManager.childPosition` / `parentPosition`（测试 `negativeCoordinatesUseFloorParentSemantics` 已覆盖）。
- 权威“child 存在但未构建 → 视为 NULL，直到 request 完成”↔ 我方未构建子节点保持 `NULL_GEOMETRY_ID`。

## 测试

`WorldHierarchyInputTest`（8 个用例）：

- 深 leaf 单独上报仅注册世界 root，leaf 作为有界 pending，自顶向下补链后 materialise（无静默丢弃）。
- child existence 来自世界 mask 而非 READY 子项；未构建子节点为 NULL geometry。
- 内部节点移除只清 geometry，保留拓扑子树与活跃后代。
- 整 root 移除回收整树，节点计数归零。
- 容量耗尽按 LRU 回收整 root，被回收 root 的 geometry exactly-once release。
- geometry-before-parent 乱序重放到 materialise 的节点。
- level-0 section 永不获得 children。
- 固定随机种子（`0x5153F00D`）4000 步随机 mask/churn，周期性 `verifyIntegrity()`。

验收命令与结果（先 focused，再全量）：

```powershell
.\gradlew.bat test --offline --console=plain --tests "com.y271727uy.voxy.client.core.rendering.hierachical.WorldHierarchyInputTest"
.\gradlew.bat test --offline --console=plain --rerun-tasks
```

全量门禁：**366 tests / 73 suites / 0 failures / 0 errors / 0 skips**（基线 357/72，本轮 +1 suite / +9 tests）。未运行 `runClient`。

## GPT-5.5 接入时必须遵守的调用顺序

1. 构造一个 `WorldHierarchyInput.forAsync(asyncNodeManager, WorldEngine.MAX_LOD_LEVEL, rootCap)`，
   `rootCap` 不得超过 `GpuTraversalShadowRuntime.MAX_NODES` 的 root 预算。
2. 世界事件驱动（不再每帧喂 `selectedKeys` 当 roots）：
   - section 就绪/更新：`updateSection(section.key(), (byte) section.nonEmptyChildren(), geometryId)`，
     未构建传 `NULL_GEOMETRY_ID`，真实空 mesh 传 `EMPTY_GEOMETRY_ID`。
   - section 卸载/失效：内部用 `removeSection(key)`（保留拓扑）；world root 退出用 `removeRoot(rootKey)`。
3. render thread 每帧仍只：`pollSyncBatch()` → `driver.uploadNodesAndRoots(batch)` →
   遍历 `batch.releasedGeometryIds()` 执行 release；**poll 之后不得**再进入 manager capture 或等待 worker。
4. 不改 `renderingEnabled` 默认值、不将 GPU backend 切到 AVAILABLE、不让 GPU queue 接管可见绘制、
   `RenderBackendPolicy` 保持 `COMPATIBILITY_ONLY`。

## 尚未解决的边界（交主协调 Agent / 后续 L19 sub-gate）

- 本门面提供**输入拓扑**；CPU 可见性契约到 GPU frustum/Hi-Z 语义的等价映射、把 shadow mismatch 降到 0 仍未做（这属于渲染线 + 联合调参）。
- `updateSection` 的 `geometryId` 由调用方（渲染线的 geometry registry）分配，本类不管理 geometry id 生命周期，只透传。
- 若渲染线需要“按 world root 批量查询当前 live roots”或增量拓扑 diff API，请写入回执由主协调 Agent 决定，不要跨所有权直接改本包。

（未改总交接单，交主协调 Agent 审查并与 GPT-5.5 渲染线合并。）
