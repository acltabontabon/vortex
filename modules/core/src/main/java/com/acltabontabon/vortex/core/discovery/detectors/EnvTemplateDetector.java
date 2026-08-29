package com.acltabontabon.vortex.core.discovery.detectors;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads only the variable <em>names</em> an environment template declares — never a value, even one
 * shaped like a {@code ${VAR}} reference, because a template someone hand-edited can still carry a
 * literal secret pasted in by mistake. See {@code docs/04-reference/project-discovery.adoc}, "Secret
 * handling."
 *
 * <p>Matched by filename only ({@code .env.example}/{@code .env.template}/{@code .env.sample}) — a
 * real {@code .env} is never a candidate this detector, or the snapshot that feeds it, will read.
 */
public final class EnvTemplateDetector implements ProjectDetector {

    private static final Pattern KEY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=");
    private static final Set<String> TEMPLATE_NAMES =
            Set.of(".env.example", ".env.template", ".env.sample");

    @Override
    public String name() {
        return "Environment template";
    }

    @Override
    public List<Finding> detect(ProjectSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (ProjectFile file : snapshot.files()) {
            if (!TEMPLATE_NAMES.contains(fileName(file.relativePath()))) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            for (String line : file.content().split("\\R")) {
                Matcher matcher = KEY.matcher(line.trim());
                if (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            findings.add(new Finding(FindingKind.ENV_TEMPLATE, file.relativePath(),
                    List.of(names.size() + " variable name(s) declared: " + String.join(", ", names)),
                    Confidence.HIGH, Map.of("names", String.join(",", names))));
        }
        return findings;
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
