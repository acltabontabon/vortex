package com.acltabontabon.vortex.core.discovery.detectors;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads a root {@code pom.xml} with the JDK's own DOM parser.
 *
 * <p>No XML library dependency is needed for this — {@code javax.xml.parsers} ships with the JDK —
 * which is exactly why this detector can live in {@code vortex-core} at all: ADR-013 forbids a
 * compile dependency here, not the use of a document format.
 */
public final class MavenPomDetector implements ProjectDetector {

    private static final String POM_PATH = "pom.xml";
    private static final int MAX_SPRING_BOOT_DEPENDENCIES_SHOWN = 5;

    @Override
    public String name() {
        return "Maven";
    }

    @Override
    public List<Finding> detect(ProjectSnapshot snapshot) {
        Optional<ProjectFile> pom = snapshot.file(POM_PATH);
        if (pom.isEmpty()) {
            return List.of();
        }

        Document document;
        try {
            document = parse(pom.get().content());
        } catch (Exception e) {
            return List.of(new Finding(FindingKind.BUILD_TOOL_MAVEN, POM_PATH,
                    List.of("pom.xml could not be parsed: " + e.getMessage()), Confidence.LOW,
                    Map.of()));
        }

        List<Finding> findings = new ArrayList<>();
        Element root = document.getDocumentElement();
        String artifactId = childText(root, "artifactId");
        String description = childText(root, "description");

        List<String> pomEvidence = new ArrayList<>();
        pomEvidence.add(artifactId.isBlank() ? "pom.xml found" : "artifactId: " + artifactId);
        findings.add(new Finding(FindingKind.BUILD_TOOL_MAVEN, POM_PATH, pomEvidence, Confidence.HIGH,
                attributesFor(artifactId, description)));

        List<String> springEvidence = springBootEvidence(root);
        if (!springEvidence.isEmpty()) {
            findings.add(new Finding(FindingKind.FRAMEWORK_SPRING_BOOT, POM_PATH, springEvidence,
                    Confidence.HIGH, Map.of()));
        }

        return findings;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        if (child == null || child.getTextContent() == null) {
            return "";
        }
        return child.getTextContent().trim();
    }

    private static Element childElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static Map<String, String> attributesFor(String artifactId, String description) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!artifactId.isBlank()) {
            attributes.put("artifactId", artifactId);
        }
        if (!description.isBlank()) {
            attributes.put("description", description);
        }
        return attributes;
    }

    private static List<String> springBootEvidence(Element root) {
        List<String> evidence = new ArrayList<>();

        Element parent = childElement(root, "parent");
        if (parent != null && "spring-boot-starter-parent".equals(childText(parent, "artifactId"))) {
            evidence.add("parent: spring-boot-starter-parent");
        }

        Element dependencies = childElement(root, "dependencies");
        if (dependencies != null) {
            NodeList entries = dependencies.getChildNodes();
            int shown = 0;
            for (int i = 0; i < entries.getLength() && shown < MAX_SPRING_BOOT_DEPENDENCIES_SHOWN; i++) {
                if (!(entries.item(i) instanceof Element dependency)
                        || !"dependency".equals(dependency.getTagName())) {
                    continue;
                }
                String artifactId = childText(dependency, "artifactId");
                if (artifactId.startsWith("spring-boot-starter")) {
                    evidence.add("dependency: " + artifactId);
                    shown++;
                }
            }
        }
        return evidence;
    }
}
