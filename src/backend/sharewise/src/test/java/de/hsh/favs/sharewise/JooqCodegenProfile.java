package de.hsh.favs.sharewise;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The effective jOOQ code-generation configuration of each execution of the {@code jooq-codegen}
 * Maven profile, read from {@code pom.xml}.
 *
 * <p>Maven merges the plugin-level {@code <configuration>} into every execution's configuration:
 * elements are matched by name and merged recursively, and the execution's values win. This
 * class applies that merge to the POM's XML and loads the result with jOOQ's own configuration
 * reader, because the plugin's parameters are the elements of jOOQ's codegen configuration. A
 * check built on it therefore uses every generator setting the Maven build uses, and a setting
 * changed only in the POM changes the check as well.
 *
 * <p>The JDBC connection is left out, since the caller supplies its own. {@code ${project.basedir}}
 * resolves to the POM's directory. Anything whose Maven semantics this class does not reproduce
 * (other property placeholders, {@code combine.*} merge attributes and the plugin-only
 * parameters) is rejected rather than approximated, so the check cannot silently diverge from
 * the build.
 */
final class JooqCodegenProfile {

    private static final String PLUGIN_XPATH = "/project/profiles/profile[id='jooq-codegen']/build/plugins/plugin"
        + "[groupId='org.jooq' and artifactId='jooq-codegen-maven']";
    private static final Set<String> CONNECTION_PARAMETERS = Set.of("jdbc");
    private static final Set<String> PLUGIN_ONLY_PARAMETERS = Set.of("skip", "configurationFile", "configurationFiles",
        "project");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    /** One execution of the profile with its effective generator configuration. */
    record Execution(String id, Configuration configuration) {

        @Override
        public String toString() {
            return id;
        }
    }

    private JooqCodegenProfile() {
    }

    /** Reads the executions of the profile in the given POM, in declaration order. */
    static List<Execution> executions(Path pom) throws Exception {
        Path baseDirectory = pom.toAbsolutePath().getParent();
        try (InputStream in = Files.newInputStream(pom)) {
            return executions(in, baseDirectory);
        }
    }

    /** Reads the executions from POM content, resolving {@code ${project.basedir}} to the given directory. */
    static List<Execution> executions(InputStream pom, Path baseDirectory) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom);
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList plugins = (NodeList) xpath.evaluate(PLUGIN_XPATH, document, XPathConstants.NODESET);
        if (plugins.getLength() != 1) {
            throw new IllegalStateException("expected one jooq-codegen-maven plugin in the jooq-codegen profile, found "
                + plugins.getLength());
        }
        Element plugin = (Element) plugins.item(0);
        Element shared = child(plugin, "configuration");
        NodeList executions = (NodeList) xpath.evaluate("executions/execution", plugin, XPathConstants.NODESET);

        List<Execution> result = new ArrayList<>();
        for (int index = 0; index < executions.getLength(); index++) {
            Element execution = (Element) executions.item(index);
            String id = xpath.evaluate("id", execution).trim();
            Element own = child(execution, "configuration");
            Element effective = own == null ? document.createElement("configuration") : (Element) own.cloneNode(true);
            if (shared != null) {
                mergeInto(effective, shared);
            }
            result.add(new Execution(id, configuration(id, effective, baseDirectory)));
        }
        return result;
    }

    /** Maven's default configuration merge: same-named children merge in order, the dominant value wins. */
    private static void mergeInto(Element dominant, Element recessive) {
        rejectMergeAttributes(dominant);
        rejectMergeAttributes(recessive);
        List<Element> dominantChildren = children(dominant);
        List<Element> recessiveChildren = children(recessive);
        if (dominantChildren.isEmpty() && recessiveChildren.isEmpty()) {
            if (dominant.getTextContent().isBlank()) {
                dominant.setTextContent(recessive.getTextContent());
            }
            return;
        }
        Map<String, Deque<Element>> dominantByName = new HashMap<>();
        for (Element child : dominantChildren) {
            dominantByName.computeIfAbsent(child.getTagName(), name -> new ArrayDeque<>()).add(child);
        }
        for (Element child : recessiveChildren) {
            Deque<Element> matches = dominantByName.get(child.getTagName());
            if (matches == null) {
                dominant.appendChild(dominant.getOwnerDocument().importNode(child, true));
            } else if (!matches.isEmpty()) {
                mergeInto(matches.poll(), child);
            }
        }
    }

    private static void rejectMergeAttributes(Element element) {
        if (element.hasAttribute("combine.children") || element.hasAttribute("combine.self")) {
            throw new IllegalStateException("combine.* attributes in the jooq-codegen configuration are not supported"
                + " by the generated-schema check: <" + element.getTagName() + ">");
        }
    }

    private static Configuration configuration(String executionId, Element effective, Path baseDirectory)
        throws Exception {
        for (Element parameter : children(effective)) {
            String name = parameter.getTagName();
            if (CONNECTION_PARAMETERS.contains(name)) {
                effective.removeChild(parameter);
            } else if (PLUGIN_ONLY_PARAMETERS.contains(name)) {
                throw new IllegalStateException("execution " + executionId + " sets <" + name + ">, which the"
                    + " generated-schema check cannot reproduce");
            }
        }
        String xml = resolvePlaceholders(executionId, serialize(effective), baseDirectory);
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return GenerationTool.load(in);
        } catch (IOException exception) {
            throw new IllegalStateException("execution " + executionId + " has no loadable jOOQ configuration",
                exception);
        }
    }

    private static String resolvePlaceholders(String executionId, String xml, Path baseDirectory) {
        Matcher matcher = PLACEHOLDER.matcher(xml);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            if (!matcher.group(1).equals("project.basedir")) {
                throw new IllegalStateException("execution " + executionId + " uses the property ${" + matcher.group(1)
                    + "} outside <jdbc>, which the generated-schema check cannot resolve");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(baseDirectory.toString()));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String serialize(Element element) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(out));
        return out.toString();
    }

    private static Element child(Element parent, String name) {
        for (Element child : children(parent)) {
            if (child.getTagName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }
}
