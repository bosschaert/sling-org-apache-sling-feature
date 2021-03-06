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

import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.osgi.annotation.versioning.ConsumerType;

/**
 * A feature extension handler can merge a feature of a particular type
 * and also post process the final assembled feature.
 */
@ConsumerType
public interface FeatureExtensionHandler {

    /**
     * Checks whether this merger can merge extensions with that name
     * @param extension The extension
     * @return {@code true} if merger can handle this
     */
    boolean canMerge(Extension extension);

    /**
     * Merge the source extension into the target extension.
     *
     * The caller of this method already ensured that both
     * extensions share the same name and type and that
     * {@link #canMerge(Extension)} returned {@code true}.
     *
     * @param target The target feature
     * @param source The source feature
     * @param extension The extension
     * @throws IllegalStateException If the extensions can't be merged
     */
    void merge(Feature target, Feature source, Extension extension);

    /**
     * Post process the feature with respect to the extension.
     * Post processing is invoked after all extensions have been merged.
     * This method is called regardless whether {@link #canMerge(Extension)} returned {@code true} or not.
     * @param feature The feature
     * @param extension The extension
     * @throws IllegalStateException If post processing failed
     */
    void postProcess(Feature feature, Extension extension);
}
