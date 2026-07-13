# GPT-5.5 交接单：L19 GPU/CPU 可见性一致性与运行门禁

更新时间：2026-07-13 15:00 Asia/Taipei

## 你的角色

你负责 L19 的“渲染与验证线”：定位 GPU shadow 与 CPU authoritative traversal 的 mismatch，接入 Claude 提供的完整层次输入，逐级验证 frustum、LOD descend、Hi-Z 和 queue 输出，直到影子结果能够持续一致。

在门禁达到前，GPU 只能运行 shadow validation，绝不能接管画面。主协调 Agent 负责最终是否允许激活。

## 项目与权威来源

- 工作区：`D:\document\dev\GitHub\voxy-1.20.1`
- 目标平台：Minecraft 1.20.1 Forge 47.4.x，Java 17。
- 权威移植来源：
  - `参考项目/voxy-mc_1201`
  - `参考项目/voxy-neoforge-1.21.1`
- Distant Horizons 只能作为参考，不能替换 Voxy renderer 或成为核心依赖。
- 开始前阅读 `nightly-handoff.txt` 顶部 L19，以及 `handoff-claude-l19.md` 的接口边界。

## 当前基线与真实运行证据

- 357 tests / 72 suites / 0 failures/errors/skips。
- `build`、`jar`、`reobfJar` 已通过。
- 最终 JAR：735836 bytes，370 production classes，8 个生产 shader，无 mappings.tsrg。
- Intel Iris Xe / OpenGL 4.6 已运行生产 shadow path：833 dispatch / 830 readback。
- CPU 画面保持 `uncovered=0`、`l13BuildFailureOccurrences=0`。
- shadow correctness 未通过：固定观察窗只有 1 match、263 mismatch，后续累计到 829 mismatch。
- 当前原因不是 GL 链未运行，而是平铺 `frameCoverage.selectedKeys()` roots、GPU shader descend/Hi-Z/frustum 与 CPU visibility contract 不等价。

首次运行曾因上游 `debugDumpNode` 依赖 GPU printf processor 导致 Intel shader compile 失败；生产 `node.glsl` 已将未使用的 debug 函数改为 no-op。不要恢复未受保护的 `printf`。

## 已有渲染实现

重点阅读：

- `render/mesh/GpuSectionRenderer.java`
- `render/mesh/GpuTraversalShadowRuntime.java`
- `render/mesh/LwjglGpuTraversalShadowDriver.java`
- `render/mesh/HierarchicalOcclusionTraverser.java`
- `render/mesh/LodCoverageSelector.java`
- `render/gl/RasterHiZBuffer.java`
- `render/gl/HiZLayoutPlanner.java`
- `render/gl/RenderBackendPolicy.java`
- `core/rendering/hierachical/GpuTraversalComputeProgram.java`
- `GpuTraversalQueueReadback.java`
- `GpuTraversalShadowController.java`
- `src/main/resources/assets/voxy/shaders/lod/hierarchical/**`
- 权威 `HiZBuffer.java`、`HierarchicalOcclusionTraverser.java`、`traversal_dev.comp`、`screenspace.glsl`。

当前 GL 链已经具备：

- 普通主 framebuffer depth texture -> raster Hi-Z。
- 208-byte std140 scene UBO。
- node/root SSBO 全量与增量上传。
- direct + indirect ping-pong compute dispatch。
- 三槽 staging/fence 非阻塞 readback，真实 frame id。
- CPU/GPU set comparison、warmup、timeout、overflow/failure fail-closed。
- 完整 GL 状态保存恢复，包括 scissor/cull/depth/texture/sampler/framebuffer。

## 你必须完成的任务

1. 先增加可诊断的分阶段 mismatch 数据，不要盲改 shader：至少分别记录 root/input、frustum 后、LOD descend 后、Hi-Z 后和最终 render queue 的计数/差集。
2. 核对 CPU `HierarchicalOcclusionTraverser`、Embeddium `isBoxVisible` 与 GPU `outsideFrustum()` 的边界语义、坐标单位、AABB、near plane 和矩阵乘法顺序。
3. 核对 scene UBO：MVP column-major、camera section/subsection、32-block section 单位、render distance 平方、frustum plane 顺序、packed Hi-Z width/height。
4. 核对 raster Hi-Z：尺寸向下 2 次幂是权威生产 ABI；逐 mip source/base/max level、保守深度方向、barrier 和 framebuffer attachment 必须保持。
5. 将 Claude 的完整 READY/world hierarchy payload 接入 shadow runtime，停止每帧把 CPU frontier 当独立 top-level roots。不要在渲染线重新推断 child mask。
6. shadow compare 必须按相同 frame id 和相同 geometry generation 比较。pending readback 不得算 mismatch，stale/duplicate 不得重复比较。
7. 对 mismatch 提供稳定可扫描指标：CPU-only、GPU-only、按 LOD 统计、首批有限样本。禁止每帧无限日志。
8. shader/GL/readback/geometry lookup 任一失败必须永久关闭 shadow runtime，本帧和后续画面仍走原 CPU renderer。
9. Oculus/MakeUp 路径本阶段保持隔离。先关闭普通 framebuffer 一致性，再单独规划 Oculus depth/shadow。
10. 不得因为 dispatch/readback 非零就宣布正确；必须以持续 shadow equality 为准。

## 文件所有权

你可以修改：

- `src/main/java/com/y271727uy/voxy/client/render/mesh/GpuSectionRenderer.java`
- `GpuTraversalShadowRuntime.java`
- `LwjglGpuTraversalShadowDriver.java`
- `HierarchicalOcclusionTraverser.java`，仅在有证据说明 CPU contract 本身错误时。
- `src/main/java/com/y271727uy/voxy/client/render/gl/**`
- `src/main/resources/assets/voxy/shaders/**`
- 对应 `src/test/java/.../render/**` 测试。

不要修改：

- `client/core/rendering/hierachical/NodeManager.java`
- `NodeStore.java`
- `AsyncNodeManager.java`
- 存储、导入器、配置页面。
- `renderingEnabled` 默认值。
- `nightly-handoff.txt`。

如果 Claude 的 API 不足，把需求写入结果回执，由主协调 Agent 分派，不要跨所有权直接改 core hierarchy。

## 激活门槛

在以下条件全部满足前，`GpuBackendAvailability` 必须保持 `COMPATIBILITY_ONLY`：

- warmup 后至少 600 个连续 shadow frame 完全匹配。
- CPU-only=0、GPU-only=0、overflow=0、timeout=0、stale generation=0。
- `uncovered=0`、`l13BuildFailureOccurrences=0`。
- CPU visible terrain 仍持续非零。
- shader、GL debug、framebuffer、readback、lifecycle 无失败。
- world root 加载/卸载和视角移动时仍能重新通过门禁。

即使达到门槛，也先提交“允许进入候选激活”的结果，由主协调 Agent决定是否切换；不要自行启用 GPU draw。

## 测试与 runClient 规则

先运行 focused 和全量测试：

```powershell
.\gradlew.bat test build reobfJar --offline --console=plain --rerun-tasks
```

你独占 `runClient`。每次运行必须：

1. 临时添加 `--quickPlaySingleplayer 新的世界`，不得创建新世界。
2. 通过 JDK17 路径和已加载 `lwjgl.dll/glfw.dll` 精确识别本次 Minecraft PID。
3. 第一条有效 metrics 后观察 10 次，每次间隔 2 秒。
4. 只 `Stop-Process -Id <本次客户端PID>`，不得杀 Gradle daemon、其他 Java 或按名称批量杀进程。
5. 每次结束都移除 quick-play 和 Forge 自动补入的：
   - `sectionRenderDistance`
   - `ingestQueueCapacity`
   - `storageBackend`
   - `storageQueueCapacity`
6. 恢复并复核：
   - `run/config/voxy-client.toml`：`renderingEnabled=true`
   - `run/options.txt`：`renderDistance:12`、`pauseOnLostFocus:true`
   - `run/config/oculus.properties`：`shaderPack=`

拒绝运行结果的条件：shader compile/link error、GL error、framebuffer incomplete、shadow runtime disabled、客户端提前退出、`uncovered!=0`、L13 build failure、只有 dispatch 没有 readback。

## 禁止事项

- 不得切换 GPU draw backend。
- 不得修改默认开启策略。
- 不得把 mismatch 隐藏为 tolerance；集合必须精确一致。
- 不得用同步 `glFinish` 或阻塞 fence wait 让 readback 看似稳定。
- 不得引入实验性 `hiz.comp`、cleaner/debug/gl46 全家桶来扩大范围。
- 不得 reset/clean/revert 工作树。

## 结果回执

完成后新建 `handoff-gpt55-result.md`，写清：

- mismatch 根因和证据。
- 修改文件与 shader ABI 变化。
- Claude payload 的消费顺序。
- focused/full tests 数量。
- 每次 runClient 的 PID、有效/拒绝原因和最终 metrics。
- 是否达到 600 连续匹配；未达到必须明确保持 compatibility-only。
- 下一步最小动作。

不要直接改总交接单；由主协调 Agent审查并合并。
