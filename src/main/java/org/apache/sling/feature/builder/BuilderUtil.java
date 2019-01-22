/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.builder;

import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Bundles;
import org.apache.sling.feature.Configuration;
import org.apache.sling.feature.Configurations;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.osgi.framework.Version;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.json.JsonWriter;

/**
 * Utility methods for the builders
 */
class BuilderUtil {
    /** Can be used in artifact metadata to specify an alias. Format: String, multiple aliases can be comma-separated. */
    static final String ARTIFACT_ID_ALIAS = "alias";

    /** Used in override rule to select all candidates. */
    static final String OVERRIDE_SELECT_ALL = "ALL";

    /** Used in override rule to select the candidate with the highest version (OSGi version comparison rules). */
    static final String OVERRIDE_SELECT_HIGHEST = "HIGHEST";

    /** Used in override rule to select the last candidate applied. */
    static final String OVERRIDE_SELECT_LATEST = "LATEST";

    /** Used in override rule to have it apply to all artifacts. */
    static final String CATCHALL_OVERRIDE = "*:*:";

    static boolean contains(String key, Iterable<Map.Entry<String, String>> iterable) {
        if (iterable != null) {
            for (Map.Entry<String, String> entry : iterable) {
                if (key.equals(entry.getKey())) {
                    return true;
                }
            }
        }
        return false;
    }

    static String get(String key, Iterable<Map.Entry<String, String>> iterable) {
        if (iterable != null) {
            for (Map.Entry<String, String> entry : iterable) {
                if (key.equals(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static void mergeWithContextOverride(String type, Map<String,String> target, Map<String,String> source, Iterable<Map.Entry<String,String>> context) {
        Map<String,String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : target.entrySet()) {
            result.put(entry.getKey(), contains(entry.getKey(), context) ? get(entry.getKey(), context) : entry.getValue());
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (contains(entry.getKey(), context)) {
                result.put(entry.getKey(), get(entry.getKey(), context));
            }
            else {
                String value = source.get(entry.getKey());
                if (value != null) {
                    String targetValue = target.get(entry.getKey());
                    if (targetValue != null) {
                        if (!value.equals(targetValue)) {
                            throw new IllegalStateException(String.format("Can't merge %s '%s' defined twice (as '%s' v.s. '%s') and not overridden.", type, entry.getKey(), value, targetValue));
                        }
                    }
                    else {
                        result.put(entry.getKey(), value);
                    }
                }
                else if (!contains(entry.getKey(), target.entrySet())) {
                    result.put(entry.getKey(), value);
                }
            }
        }
        target.clear();
        target.putAll(result);
    }

    // variables
    static void mergeVariables(Map<String,String> target, Map<String,String> source, BuilderContext context) {
        mergeWithContextOverride("Variable", target, source,
                (null != context) ? context.getVariablesOverrides().entrySet() : null);
    }

    /**
     * Merge bundles from source into target
     *
     * @param target             The target bundles
     * @param source             The source bundles
     * @param sourceFeature Optional, if set origin will be recorded
     * @param artifactMergeAlg   Algorithm used to merge the artifacts
     * @param originKey          An optional key used to track origins of merged bundles
     */
    static void mergeBundles(final Bundles target,
        final Bundles source,
        final Feature sourceFeature,
        final List<String> artifactOverrides,
        final String originKey) {
        for(final Map.Entry<Integer, List<Artifact>> entry : source.getBundlesByStartOrder().entrySet()) {

            for(final Artifact a : entry.getValue()) {
                List<ArtifactId> artifactIds = getArtifactIDsIncludingAliases(a);

                List<Artifact> allExisting = new ArrayList<>();
                for (final ArtifactId id : artifactIds) {
                    Artifact s = target.getSame(id);
                    // Find aliased bundles in target
                    if (s != null) {
                        allExisting.add(s);
                    }

                    allExisting.addAll(findAliasedBundles(id, target));
                }

                final List<Artifact> selectedArtifacts = new ArrayList<>();
                for (final Artifact existing : allExisting) {
                    if (sourceFeature.getId().toMvnId().equals(existing.getMetadata().get(originKey))) {
                        // If the source artifact came from the same feature, keep them side-by-side
                        selectedArtifacts.addAll(Arrays.asList(existing, a));
                    } else {
                        selectedArtifacts.addAll(selectArtifactOverride(existing, a, artifactOverrides));
                        while(target.removeSame(existing.getId())) {
                            // Keep executing removeSame() which ignores the version until last one was removed
                        }
                    }
                }

                if (selectedArtifacts.isEmpty()) {
                    selectedArtifacts.add(a);
                }

                for (Artifact sa : selectedArtifacts) {
                    // create a copy to detach artifact from source
                    final Artifact cp = sa.copy(sa.getId());
                    // Record the original feature of the bundle, if needed
                    if (originKey != null) {
                        if (sourceFeature != null && source.contains(sa) && sa.getMetadata().get(originKey) == null) {
                            cp.getMetadata().put(originKey, sourceFeature.getId().toMvnId());
                        }
                    }
                    target.add(cp);
                }
            }
        }
    }

    static List<Artifact> selectArtifactOverride(Artifact a1, Artifact a2, List<String> artifactOverrides) {
        if (a1.getId().equals(a2.getId())) {
            // They're the same so return one of them
            return Collections.singletonList(a2);
        }

        Set<String> commonPrefixes = getCommonPrefixes(a1, a2);
        if (commonPrefixes.isEmpty()) {
            throw new IllegalStateException("Internal error selecting override. No common prefix between " + a1 + " and " + a2);
        }

        Set<Artifact> result = new LinkedHashSet<>();
        for (String prefix : commonPrefixes) {
            for (String o : artifactOverrides) {
                if (o.startsWith(prefix) || o.startsWith(CATCHALL_OVERRIDE)) {
                    int idx = o.lastIndexOf(':');
                    if (idx <= 0 || o.length() <= idx)
                        continue;

                    String rule = o.substring(idx+1).trim();

                    if (OVERRIDE_SELECT_ALL.equals(rule)) {
                        return Arrays.asList(a1, a2);
                    } else if (OVERRIDE_SELECT_HIGHEST.equals(rule)) {
                        Version a1v = a1.getId().getOSGiVersion();
                        Version a2v = a2.getId().getOSGiVersion();
                        return a1v.compareTo(a2v) > 0 ? Collections.singletonList(a1) : Collections.singletonList(a2);
                    } else if (OVERRIDE_SELECT_LATEST.equals(rule)) {
                        return Collections.singletonList(a2);
                    }

                    // The rule must represent a version
                    // See if its one of the existing artifact. If so use those, as they may have additional metadata
                    if (a1.getId().getVersion().equals(rule)) {
                        result.add(a1);
                    } else if (a2.getId().getVersion().equals(rule)) {
                        result.add(a2);
                    } else {
                        // It's a completely new artifact
                        result.add(new Artifact(ArtifactId.fromMvnId(o)));
                    }
                }
            }
        }
        if (result.size() > 0) {
            return new ArrayList<>(result);
        }

        throw new IllegalStateException("Artifact override rule required to select between these two artifacts " +
            a1 + " and " + a2 + ". The rule must be specified for " + commonPrefixes);
    }

    private static Set<String> getCommonPrefixes(Artifact a1, Artifact a2) {
        Set<String> a1Prefixes = getPrefixesIncludingAliases(a1);
        Set<String> a2Prefixes = getPrefixesIncludingAliases(a2);

        a1Prefixes.retainAll(a2Prefixes);
        return a1Prefixes;
    }

    private static Set<String> getPrefixesIncludingAliases(Artifact a) {
        Set<String> prefixes = new HashSet<>();
        for (ArtifactId aid : getArtifactIDsIncludingAliases(a)) {
            String id = aid.toMvnId();
            prefixes.add(id.substring(0, id.lastIndexOf(':') + 1));
        }
        return prefixes;
    }

    private static List<ArtifactId> getArtifactIDsIncludingAliases(Artifact a) {
        List<ArtifactId> artifactIds = new ArrayList<>();
        artifactIds.add(a.getId());

        String aliases = a.getMetadata().get(ARTIFACT_ID_ALIAS);
        if (aliases != null) {
            for (String alias : aliases.split(",")) {
                alias = alias.trim();
                if (alias.indexOf(':') == alias.lastIndexOf(':')) {
                    // No version provided, set to version zero
                    alias += ":0.0.0";
                }
                artifactIds.add(ArtifactId.fromMvnId(alias));
            }
        }
        return artifactIds;
    }

    private static List<Artifact> findAliasedBundles(ArtifactId id, Bundles bundles) {
        List<Artifact> result = new ArrayList<>();

        String prefix = id.getGroupId() + ":" + id.getArtifactId() + ":";
        for (Artifact a : bundles) {
            String aliases = a.getMetadata().get(ARTIFACT_ID_ALIAS);
            if (aliases != null) {
                for (String alias : aliases.split(",")) {
                    alias = alias.trim();
                    if (alias.startsWith(prefix)) {
                        result.add(a);
                    }
                }
            }
        }
        return result;
    }

    // configurations - merge / override
    static void mergeConfigurations(final Configurations target, final Configurations source) {
        for(final Configuration cfg : source) {
            boolean found = false;
            for(final Configuration current : target) {
                if ( current.compareTo(cfg) == 0 ) {
                    found = true;
                    // merge / override properties
                    final Enumeration<String> i = cfg.getProperties().keys();
                    while ( i.hasMoreElements() ) {
                        final String key = i.nextElement();
                        current.getProperties().put(key, cfg.getProperties().get(key));
                    }
                    break;
                }
            }
            if ( !found ) {
                final Configuration newCfg = cfg.copy(cfg.getPid());
                target.add(newCfg);
            }
        }
    }

    // framework properties (add/merge)
    static void mergeFrameworkProperties(final Map<String,String> target, final Map<String,String> source, BuilderContext context) {
        mergeWithContextOverride("Property", target, source,
                context != null ? context.getFrameworkPropertiesOverrides().entrySet() : null);
    }

    // requirements (add)
    static void mergeRequirements(final List<Requirement> target, final List<Requirement> source) {
        for(final Requirement req : source) {
            if ( !target.contains(req) ) {
                target.add(req);
            }
        }
    }

    // capabilities (add)
    static void mergeCapabilities(final List<Capability> target, final List<Capability> source) {
        for(final Capability cap : source) {
            if ( !target.contains(cap) ) {
                target.add(cap);
            }
        }
    }

    /**
     * Merge an extension from source into target
     *
     * @param target             The target extension
     * @param source             The source extension
     * @param originatingFeature Optional, if set origin will be recorded for artifacts
     * @param artifactMergeAlg   The merge algorithm for artifacts
     */
    static void mergeExtensions(final Extension target,
            final Extension source,
            final Feature sourceFeature,
            final List<String> artifactOverrides,
            final String originKey) {
        switch ( target.getType() ) {
            case TEXT : // simply append
                target.setText(target.getText() + "\n" + source.getText());
                break;
            case JSON : JsonStructure struct1;
                try ( final StringReader reader = new StringReader(target.getJSON()) ) {
                    struct1 = Json.createReader(reader).read();
                }
                JsonStructure struct2;
                try ( final StringReader reader = new StringReader(source.getJSON()) ) {
                    struct2 = Json.createReader(reader).read();
                }

                if ( struct1.getValueType() != struct2.getValueType() ) {
                    throw new IllegalStateException("Found different JSON types for extension " + target.getName()
                        + " : " + struct1.getValueType() + " and " + struct2.getValueType());
                }
                if ( struct1.getValueType() == ValueType.ARRAY ) {
                    final JsonArrayBuilder builder = Json.createArrayBuilder();

                    Stream.concat(
                        ((JsonArray) struct1).stream(),
                        ((JsonArray) struct2).stream()
                    ).forEachOrdered(builder::add);

                    struct1 = builder.build();
                } else {
                    // object is merge
                    struct1 = merge((JsonObject)struct1, (JsonObject)struct2);
                }
                StringWriter buffer = new StringWriter();
                try (JsonWriter writer = Json.createWriter(buffer))
                {
                    writer.write(struct1);
                }
                target.setJSON(buffer.toString());
                break;

        case ARTIFACTS:
            for(final Artifact a : source.getArtifacts()) {
                Artifact existing = target.getArtifacts().getSame(a.getId());
                List<Artifact> selectedArtifacts = null;
                if (existing != null) {
                    if (sourceFeature.getId().toMvnId().equals(existing.getMetadata().get(originKey))) {
                        // If the source artifact came from the same feature, keep them side-by-side
                        selectedArtifacts = Arrays.asList(existing, a);
                    } else {
                        selectedArtifacts = selectArtifactOverride(existing, a, artifactOverrides);
                        while(target.getArtifacts().removeSame(existing.getId())) {
                            // Keep executing removeSame() which ignores the version until last one was removed
                        }
                    }
                } else {
                    selectedArtifacts = Collections.singletonList(a);
                }

                for (Artifact sa : selectedArtifacts) {
                    // create a copy to detach artifact from source
                    final Artifact cp = sa.copy(sa.getId());
                    // Record the original feature of the bundle if needed
                    if (originKey != null) {
                        if (sourceFeature != null && source.getArtifacts().contains(sa)
                                && sa.getMetadata().get(originKey) == null) {
                            cp.getMetadata().put(originKey, sourceFeature.getId().toMvnId());
                        }
                    }
                    target.getArtifacts().add(cp);
                }
            }
            break;
        }
    }

    // extensions (add/merge)
    static void mergeExtensions(final Feature target,
        final Feature source,
        final BuilderContext context,
        final List<String> artifactOverrides,
        final String originKey) {
        for(final Extension ext : source.getExtensions()) {
            boolean found = false;

            // Make a defensive copy of the extensions, as the handlers may modify the extensions on the target
            for(final Extension current : new ArrayList<>(target.getExtensions())) {
                if ( current.getName().equals(ext.getName()) ) {
                    found = true;
                    if ( current.getType() != ext.getType() ) {
                        throw new IllegalStateException("Found different types for extension " + current.getName()
                            + " : " + current.getType() + " and " + ext.getType());
                    }
                    boolean handled = false;
                    for(final MergeHandler me : context.getMergeExtensions()) {
                        if ( me.canMerge(current) ) {
                            me.merge(new HandlerContextImpl(context, me), target, source, current, ext);
                            handled = true;
                            break;
                        }
                    }
                    if ( !handled ) {
                        // default merge
                        mergeExtensions(current, ext, source, artifactOverrides, originKey);
                    }
                }
            }
            if ( !found ) {
                // The extension isn't found in the target, still call merge to allow handlers to operate on the
                // first extension being aggregated
                boolean handled = false;
                for (final MergeHandler mh : context.getMergeExtensions()) {
                    if (mh.canMerge(ext)) {
                        mh.merge(new HandlerContextImpl(context, mh), target, source, null, ext);
                        handled = true;
                        break;
                    }
                }
                if ( !handled ) {
                    // no merge handler, just add
                    target.getExtensions().add(ext);
                }
            }
        }
        // post processing
        for(final Extension ext : target.getExtensions()) {
            for(final PostProcessHandler ppe : context.getPostProcessExtensions()) {
                ppe.postProcess(new HandlerContextImpl(context, ppe), target, ext);
            }
        }
    }

    private static JsonObject merge(final JsonObject obj1, final JsonObject obj2) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        for (final Map.Entry<String, JsonValue> entry : obj1.entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }
        for(final Map.Entry<String, JsonValue> entry : obj2.entrySet()) {
            if ( !obj1.containsKey(entry.getKey()) ) {
                builder.add(entry.getKey(), entry.getValue());
            } else {
                final JsonValue oldValue = obj1.get(entry.getKey());
                if ( oldValue.getValueType() != entry.getValue().getValueType() ) {
                    // new type wins
                    builder.add(entry.getKey(), entry.getValue());
                } else if ( oldValue.getValueType() == ValueType.ARRAY ) {
                    final JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();

                    Stream.concat(
                        ((JsonArray) oldValue).stream(),
                        ((JsonArray)entry.getValue()).stream()
                    ).forEachOrdered(arrayBuilder::add);

                    builder.add(entry.getKey(), arrayBuilder.build());
                } else if ( oldValue.getValueType() == ValueType.OBJECT ) {
                    builder.add(entry.getKey(), merge((JsonObject)oldValue, (JsonObject)entry.getValue()));
                } else {
                    builder.add(entry.getKey(), entry.getValue());
                }
            }
        }
        return builder.build();
    }

    private static class HandlerContextImpl implements HandlerContext {
        private final ArtifactProvider artifactProvider;
        private final Map<String,String> configuration;

        public HandlerContextImpl(BuilderContext bc, MergeHandler handler) {
            artifactProvider = bc.getArtifactProvider();
            configuration = getHandlerConfiguration(bc, handler);
        }

        public HandlerContextImpl(BuilderContext bc, PostProcessHandler handler) {
            artifactProvider = bc.getArtifactProvider();
            configuration = getHandlerConfiguration(bc, handler);
        }

        private Map<String,String> getHandlerConfiguration(BuilderContext bc, Object handler) {
            final Map<String,String> result = new HashMap<>();

            Map<String, String> overall = bc.getHandlerConfigurations().get("*");
            if (overall != null)
                result.putAll(overall);
            final String name = getHandlerName(handler);
            if (name != null) {
                Map<String, String> handlerSpecific = bc.getHandlerConfigurations().get(name);
                if (handlerSpecific != null)
                    result.putAll(handlerSpecific);
            }
            return result;
        }

        private static String getHandlerName(Object handler) {
            return handler.getClass().getSimpleName();
        }

        @Override
        public ArtifactProvider getArtifactProvider() {
            return artifactProvider;
        }

        @Override
        public Map<String,String> getConfiguration() {
            return configuration;
        }
    }
}
