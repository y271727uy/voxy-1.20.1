package com.y271727uy.voxy.common.release;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReleaseArtifactPolicyTest {
    private static final List<String> REQUIRED = List.of(
            "META-INF/MANIFEST.MF", "META-INF/mods.toml", "voxy.mixins.json",
            "voxy.refmap.json", "pack.mcmeta", "assets/voxy/lang/en_us.json",
            "assets/voxy/lang/zh_cn.json", "com/y271727uy/voxy/Voxy.class",
            "com/y271727uy/voxy/client/VoxyClientCommands.class");

    @Test
    public void acceptsRuntimeArtifactWithRealMixinMetadata() {
        assertTrue(ReleaseArtifactPolicy.evaluate(entrySizes(REQUIRED)).publishable());
    }

    @Test
    public void rejectsMissingRefmapAndGeneratedMappingIntermediate() {
        List<String> entries = new ArrayList<>(REQUIRED);
        entries.remove("voxy.refmap.json");
        entries.add("voxy.refmap.json.mappings.tsrg");

        ReleaseArtifactPolicy.Evaluation result = ReleaseArtifactPolicy.evaluate(entrySizes(entries));

        assertFalse(result.publishable());
        assertTrue(result.failures().contains("missing:voxy.refmap.json"));
        assertTrue(result.failures().contains("forbidden:voxy.refmap.json.mappings.tsrg"));
    }

    @Test
    public void normalizesZipEntrySeparators() {
        List<String> entries = REQUIRED.stream().map(entry -> entry.replace('/', '\\')).toList();
        assertTrue(ReleaseArtifactPolicy.evaluate(entrySizes(entries)).publishable());
    }

    @Test
    public void rejectsActualChineseReferenceProjectPath() {
        List<String> entries = new ArrayList<>(REQUIRED);
        entries.add("\u53c2\u8003\u9879\u76ee/voxy-mc_1201/build.gradle");

        ReleaseArtifactPolicy.Evaluation result = ReleaseArtifactPolicy.evaluate(entrySizes(entries));

        assertFalse(result.publishable());
        assertTrue(result.failures().contains(
                "forbidden:\u53c2\u8003\u9879\u76ee/voxy-mc_1201/build.gradle"));
    }

    @Test
    public void rejectsZeroByteRefmapPlaceholder() {
        Map<String, Long> entries = entrySizes(REQUIRED);
        entries.put("voxy.refmap.json", 0L);

        ReleaseArtifactPolicy.Evaluation result = ReleaseArtifactPolicy.evaluate(entries);

        assertFalse(result.publishable());
        assertTrue(result.failures().contains("empty:voxy.refmap.json"));
    }

    private static Map<String, Long> entrySizes(List<String> names) {
        Map<String, Long> entries = new LinkedHashMap<>();
        names.forEach(name -> entries.put(name, 1L));
        return entries;
    }
}
