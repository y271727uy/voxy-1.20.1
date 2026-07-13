# GPT-5.5 L19 result

Time: 2026-07-13 Asia/Taipei

## Result

GPU traversal remains shadow validation only. The 600 consecutive matching-frame gate was not reached, so `RenderBackendPolicy` must stay `GpuBackendAvailability.COMPATIBILITY_ONLY`; GPU draw must not be enabled by this handoff.

## Root cause and evidence

- The original mismatch source was a contract mismatch: the shadow runtime used the per-frame CPU visible frontier / `frameCoverage.selectedKeys()` as independent GPU roots. That is not equivalent to the authoritative world hierarchy roots and child topology consumed by the hierarchical shader.
- The runtime now consumes the READY/world hierarchy snapshot instead of a flat selected-root set. CPU visible keys are only the comparison oracle.
- After connecting the hierarchy input, the first runClient attempt exposed a second hard blocker: the shader fallback could enqueue a missing-child self node without a real mesh, which produced the invalid geometry id `16777215` (`NULL_MESH`) in readback and permanently disabled the shadow runtime.
- `traversal_dev.comp` now guards that fallback with `hasMesh(node)` before `enqueueSelfForRender(node)`. This is a behavioral guard only; it does not change shader bindings, buffer layouts, std140/std430 ABI, or dispatch ABI.

## Files changed

- `src/main/java/com/y271727uy/voxy/client/render/mesh/GpuTraversalShadowRuntime.java`
  - Added shadow diagnostics: root input, LOD-after, frustum-after, Hi-Z-after placeholder, CPU/GPU final queue counts, CPU-only/GPU-only counts, LOD summaries, and finite samples.
  - Replaced flat selected-root upload with `WorldHierarchyInput.forAsync(nodes, WorldEngine.MAX_LOD_LEVEL, MAX_NODES)`.
  - Synchronizes READY topology into `WorldHierarchyInput.updateSection(key, childMask, geometry)` and uploads async `SyncBatch` before frame dispatch.
- `src/main/java/com/y271727uy/voxy/client/render/mesh/GpuSectionRenderer.java`
  - Passes `Map.copyOf(READY_SECTIONS)` into shadow runtime and logs the new diagnostics in render metrics.
- `src/main/java/com/y271727uy/voxy/client/core/rendering/hierachical/WorldHierarchyInput.java`
  - Kept the pending-replay/top-down materialisation contract: deep leaves can remain pending until world masks arrive through parents.
- `src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp`
  - Prevents NULL/unbuilt mesh ids from entering the render queue on missing-child fallback.
- `src/test/java/com/y271727uy/voxy/client/render/mesh/GpuTraversalShadowRuntimeTest.java`
  - Covers permanent disable, diagnostic CPU-only evidence, upload-before-dispatch, and world-root upload instead of visible-cut roots.
- Cleanup after runClient:
  - Removed temporary `--quickPlaySingleplayer` from `build.gradle`.
  - Removed Forge auto-added `sectionRenderDistance`, `ingestQueueCapacity`, `storageBackend`, and `storageQueueCapacity` from `run/config/voxy-client.toml`.
  - Verified `renderingEnabled=true`, `run/options.txt` has `renderDistance:12` and `pauseOnLostFocus:true`, and `run/config/oculus.properties` has `shaderPack=`.

## Claude payload consumption order

1. Render thread snapshots `READY_SECTIONS`.
2. `GpuTraversalShadowRuntime` compares the snapshot against its local topology.
3. For each changed section it calls `WorldHierarchyInput.updateSection(key, childMask, geometryId)` or `removeSection(key)`.
4. `WorldHierarchyInput` anchors each section to a max-level world root and relies on `NodeManager` pending replay for geometry-before-parent / child-mask-before-parent order.
5. The async `SyncBatch` is polled and uploaded before `driver.runFrame`.
6. CPU visible keys are copied and submitted only as the same-frame comparison set. Pending, stale, and duplicate readbacks remain controller concerns and are not treated as successful matches.

## Verification

Successful earlier in this L19 run:

- `.\gradlew.bat test --tests "com.y271727uy.voxy.client.render.mesh.GpuTraversalShadowRuntimeTest" --offline --console=plain --rerun-tasks`: passed.
- `.\gradlew.bat test --tests "com.y271727uy.voxy.client.core.rendering.hierachical.WorldHierarchyInputTest" --offline --console=plain --rerun-tasks`: passed.
- Combined focused shadow/runtime + hierarchy tests: passed.
- `.\gradlew.bat test build reobfJar --offline --console=plain --rerun-tasks`: passed, 366 tests.

Post-cleanup rerun attempted by this handoff:

- `.\gradlew.bat test build reobfJar --offline --console=plain --rerun-tasks`: failed before tests in `:createMinecraftArtifacts` because IntelliJ PID `30592` locked `build/moddev/artifacts/forge-1.20.1-47.4.0-merged.jar`.
- `.\gradlew.bat test build reobfJar --offline --console=plain`: failed before tests in `:createMinecraftArtifacts` because Gradle daemon PID `16540` locked `build/moddev/artifacts/forge-1.20.1-47.4.0.jar`.
- I did not kill IntelliJ or Gradle daemons. No Minecraft/LWJGL client process remained during cleanup.

## runClient evidence

1. First runClient
   - Minecraft PID: `31944` (JDK17, LWJGL/GLFW loaded).
   - Rejected: shadow runtime disabled after readback.
   - Disable reason: `Invalid traversal queue readback: java.lang.IllegalArgumentException: Render queue contains invalid geometry id: 16777215`.
   - PID `31944` was stopped as the exact identified client process.

2. Second runClient after shader guard
   - Minecraft PID: `8536` (JDK17, LWJGL/GLFW loaded).
   - Shadow runtime stayed available: `gpuShadowAvailable=true`.
   - Best/final observed metrics: `gpuShadowDispatch=25`, `gpuShadowReadback=23`, `gpuShadowMatch=23`, `gpuShadowMismatch=0`, `gpuShadowDisableReason=`, `gpuShadowRootInput=1`, `gpuShadowLodAfter=1`, `gpuShadowFrustumAfter=0`, `gpuShadowHiZAfter=-1`, `gpuShadowCpuQueue=0`, `gpuShadowGpuQueue=0`, `gpuShadowCpuOnly=0`, `gpuShadowGpuOnly=0`, `uncovered=0`, `l13BuildFailureOccurrences=0`, `terrainDrawn=0`.
   - This is limited evidence only: CPU terrain was zero and only 23 matching readbacks were observed, not the required 600 consecutive nonzero-terrain frames.
   - A later `Stop-Process -Id 8536` found the process already gone.

## Gate status

- 600 consecutive matching shadow frames: not reached.
- CPU visible terrain nonzero during the accepted window: not proven.
- Hi-Z intermediate count: still `-1`; no shader stats/readback buffer has been added for the Hi-Z-after phase.
- GPU draw activation: not allowed.
- Compatibility-only policy: still mandatory.

## Next minimal action

Get a focused, non-paused runClient with visible/nonzero CPU terrain and collect at least 600 consecutive post-warmup matches with `CPU-only=0`, `GPU-only=0`, no overflow, no timeout, no stale generation, `uncovered=0`, and `l13BuildFailureOccurrences=0`. After that, add shader-side stats/readback for the real `HiZAfter` count and continue frustum/Hi-Z boundary diagnostics before proposing candidate activation.
