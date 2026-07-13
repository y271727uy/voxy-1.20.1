package com.y271727uy.voxy.common.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Release-JAR inventory contract. Entry names use the forward-slash ZIP form. */
public final class ReleaseArtifactPolicy {
    private static final Set<String> REQUIRED = Set.of(
            "META-INF/MANIFEST.MF",
            "META-INF/mods.toml",
            "voxy.mixins.json",
            "voxy.refmap.json",
            "pack.mcmeta",
            "assets/voxy/lang/en_us.json",
            "assets/voxy/lang/zh_cn.json",
            "com/y271727uy/voxy/Voxy.class",
            "com/y271727uy/voxy/client/VoxyClientCommands.class"
    );

    private ReleaseArtifactPolicy() {
    }

    public static Evaluation evaluate(Map<String, Long> entries) {
        Map<String, Long> normalized = entries.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> normalize(entry.getKey()), Map.Entry::getValue, Math::max));
        List<String> failures = new ArrayList<>();
        for (String required : REQUIRED) {
            Long size = normalized.get(required);
            if (size == null) {
                failures.add("missing:" + required);
            } else if (size <= 0) {
                failures.add("empty:" + required);
            }
        }
        normalized.keySet().stream().filter(ReleaseArtifactPolicy::isForbidden).sorted()
                .forEach(entry -> failures.add("forbidden:" + entry));
        return new Evaluation(failures.isEmpty(), List.copyOf(failures));
    }

    static boolean isForbidden(String entry) {
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mappings.tsrg")
                || lower.endsWith("test.class")
                || lower.startsWith("run/")
                || lower.startsWith("logs/")
                || lower.startsWith("crash-reports/")
                || lower.startsWith("\u53c2\u8003\u9879\u76ee/")
                || lower.startsWith("reference/")
                || lower.startsWith("references/")
                || lower.startsWith("src/")
                || lower.contains("/src/test/");
    }

    private static String normalize(String entry) {
        return entry.replace('\\', '/').replaceFirst("^/+", "");
    }

    public record Evaluation(boolean publishable, List<String> failures) {
    }
}
