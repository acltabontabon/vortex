package com.acltabontabon.vortex.core.discovery.detectors;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code Dockerfile} for its base image and exposed port.
 *
 * <p>Only an execution-target <em>hint</em>: Vortex never builds an image, so a bare Dockerfile
 * alone never becomes a proposed environment — {@code ProjectDiscoveryService} only uses its
 * exposed port as a fallback when a Compose service's own {@code ports:} entry does not resolve one.
 */
public final class DockerfileDetector implements ProjectDetector {

    private static final Pattern FROM = Pattern.compile("^FROM\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPOSE = Pattern.compile("^EXPOSE\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "Dockerfile";
    }

    @Override
    public List<Finding> detect(ProjectSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (ProjectFile file : snapshot.files()) {
            if ("Dockerfile".equals(fileName(file.relativePath()))) {
                findings.add(detectOne(file));
            }
        }
        return findings;
    }

    private Finding detectOne(ProjectFile file) {
        List<String> evidence = new ArrayList<>();
        Map<String, String> attributes = new LinkedHashMap<>();
        Confidence confidence = Confidence.MEDIUM;

        for (String line : file.content().split("\\R")) {
            String trimmed = line.trim();
            Matcher from = FROM.matcher(trimmed);
            if (from.find() && !attributes.containsKey("baseImage")) {
                attributes.put("baseImage", from.group(1));
                evidence.add("FROM " + from.group(1));
            }
            Matcher expose = EXPOSE.matcher(trimmed);
            if (expose.find() && !attributes.containsKey("exposedPort")) {
                attributes.put("exposedPort", expose.group(1));
                evidence.add("EXPOSE " + expose.group(1));
                confidence = Confidence.HIGH;
            }
        }
        if (evidence.isEmpty()) {
            evidence.add("Dockerfile present");
        }
        return new Finding(FindingKind.EXECUTION_HINT_DOCKERFILE, file.relativePath(), evidence,
                confidence, attributes);
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
